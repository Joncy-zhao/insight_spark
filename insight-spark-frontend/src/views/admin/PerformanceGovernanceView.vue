<template>
  <section class="perf-center">
    <header class="perf-hero">
      <div class="perf-hero-main">
        <el-tag class="perf-hero-tag" effect="dark" round>运维治理 · 实时配置</el-tag>
        <h1>性能治理中心</h1>
        <p>
          集中监控 JVM、磁盘、数据库与核心引擎运行状态，并在线调整压力管控、慢查询熔断、缓存策略、批处理调度、告警阈值与资源优先级。
          配置写入 is_system_config，保存后立即生效。
        </p>
        <div class="perf-hero-actions">
          <el-tag v-if="lastRefresh" type="info" effect="plain" round>上次刷新 {{ lastRefresh }}</el-tag>
          <el-tooltip content="开启后每 15 秒自动刷新监控数据" placement="top">
            <el-switch
              v-model="autoRefresh"
              active-text="自动刷新"
              inactive-text=""
              inline-prompt
              class="auto-switch"
            />
          </el-tooltip>
          <el-button type="primary" :loading="loading" :icon="Refresh" @click="loadAll">全部刷新</el-button>
        </div>
      </div>
      <div class="perf-hero-score" :class="healthScoreTone">
        <span>平台健康</span>
        <strong>{{ platformHealthScore }}</strong>
        <small>综合 JVM、慢查与告警状态</small>
      </div>
    </header>

    <nav class="module-nav" aria-label="治理模块导航">
      <button
        v-for="m in modules"
        :key="m.id"
        type="button"
        class="module-nav-item"
        :class="{ active: activeTab === m.id, 'has-badge': !!m.badge }"
        @click="goTab(m.id)"
      >
        <span class="module-nav-icon" :class="`module-nav-icon--${m.id}`">
          <el-icon><component :is="m.icon" /></el-icon>
        </span>
        <span class="module-nav-text">
          <strong>{{ m.title }}</strong>
          <small>{{ m.desc }}</small>
        </span>
        <el-badge v-if="m.badge" :value="m.badge" type="danger" class="module-nav-badge" />
      </button>
    </nav>

    <div v-if="activeModuleHint" class="module-hint">
      <el-icon class="module-hint-icon"><InfoFilled /></el-icon>
      <div class="module-hint-body">
        <strong>{{ activeModuleHint.title }}</strong>
        <p>{{ activeModuleHint.desc }}</p>
      </div>
      <el-button
        v-if="activeModuleHint.actionLabel"
        link
        type="primary"
        @click="activeModuleHint.action?.()"
      >
        {{ activeModuleHint.actionLabel }}
      </el-button>
    </div>

    <div v-loading="loading" class="perf-body">
      <!-- 1. 性能实时监控 -->
      <div v-show="activeTab === 'monitor'" class="module-pane">
        <div class="metric-grid">
          <el-card
            v-for="metric in monitorMetrics"
            :key="metric.key"
            shadow="hover"
            class="metric-card"
            :class="{ alert: metric.alert, clickable: !!metric.linkTab }"
            @click="metric.linkTab && goTab(metric.linkTab)"
          >
            <div class="metric-top">
              <span class="metric-icon-wrap" :class="`metric-icon-wrap--${metric.key}`">
                <el-icon><component :is="metric.icon" /></el-icon>
              </span>
              <el-tag v-if="metric.alert" type="danger" size="small" effect="dark">告警</el-tag>
              <el-tag v-else-if="metric.status" :type="metric.statusType" size="small">{{ metric.status }}</el-tag>
            </div>
            <div class="metric-title">{{ metric.title }}</div>
            <div class="metric-value">{{ metric.value }}</div>
            <el-progress
              v-if="metric.percent != null"
              :percentage="Math.min(100, Math.round(metric.percent))"
              :color="progressColor(metric.percent)"
              :stroke-width="8"
              striped
              striped-flow
              class="metric-bar"
            />
            <p v-if="metric.sub" class="metric-sub">{{ metric.sub }}</p>
            <span v-if="metric.linkTab" class="metric-link-hint">点击查看详情</span>
          </el-card>
        </div>

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
                  <el-button type="primary" size="small" :loading="saving.dbPressure" @click="saveDbPressure">
                    保存并生效
                  </el-button>
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
              <span>慢查询列表（{{ filteredSlowQueries.length }} / {{ slowQueries.length }}）</span>
              <div class="card-head-tools">
                <el-input
                  v-model="slowQueryFilter"
                  placeholder="搜索用户、表名或状态"
                  clearable
                  :prefix-icon="Search"
                  class="table-filter"
                />
                <el-button link type="primary" :icon="Refresh" @click="loadSlowQueries">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table :data="filteredSlowQueries" border size="small" max-height="400" highlight-current-row>
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
          <el-button type="primary" :icon="Document" @click="openGenerateDialog">生成分析报告</el-button>
          <template v-if="bottleneckReport">
            <el-button :icon="CopyDocument" @click="copyReport">复制文本</el-button>
            <el-button :icon="Download" :loading="exportingPdf" @click="exportCurrentReport('pdf')">导出 PDF</el-button>
            <el-button :icon="Download" :loading="exportingWord" @click="exportCurrentReport('word')">导出 Word</el-button>
          </template>
        </div>

        <div v-if="loadingReportPreview" class="report-preview-loading mt24" v-loading="true" element-loading-text="正在生成 PDF 预览...">
          <div class="report-preview-placeholder" />
        </div>

        <div v-else-if="reportPdfPreviewUrl" ref="reportPreviewRef" class="report-pdf-preview mt16">
          <div class="report-preview-head">
            <div>
              <strong>{{ bottleneckReport?.title || '性能瓶颈诊断报告' }}</strong>
              <p class="report-meta">
                报告编号 {{ bottleneckReport?.reportId }}
                · 生成时间 {{ bottleneckReport?.generatedAtDisplay || formatReportTime(bottleneckReport?.generatedAt) }}
              </p>
            </div>
            <el-tag
              v-if="bottleneckReport?.overallLevel"
              :type="overallLevelType(bottleneckReport.overallLevel)"
              effect="dark"
              size="large"
            >
              {{ overallLevelLabel(bottleneckReport.overallLevel) }}
            </el-tag>
          </div>
          <iframe :src="reportPdfPreviewUrl" class="report-pdf-frame" title="报告 PDF 预览" />
        </div>

        <div v-else class="guided-empty mt24">
          <el-icon class="guided-empty-icon"><TrendCharts /></el-icon>
          <strong>尚未生成瓶颈诊断报告</strong>
          <p>点击「生成分析报告」后，系统将综合慢查询、缓存命中、堆内存与拦截记录，生成可导出 PDF / Word 的正式报告。</p>
          <el-button type="primary" @click="openGenerateDialog">开始生成</el-button>
        </div>

        <el-card shadow="never" class="panel-card report-history-card mt16">
          <template #header>
            <div class="card-head">
              <span>报告记录（{{ reportHistory.length }}）</span>
              <el-button v-if="reportHistory.length" link type="danger" @click="clearReportHistory">清空记录</el-button>
            </div>
          </template>
          <el-empty v-if="!reportHistory.length" description="生成报告后将在此保留历史记录，支持再次查看与导出" />
          <el-table v-else :data="reportHistory" border size="small" highlight-current-row>
            <el-table-column prop="id" label="报告编号" min-width="170" show-overflow-tooltip />
            <el-table-column prop="generatedAt" label="生成时间" width="170" />
            <el-table-column label="等级" width="96">
              <template #default="{ row }">
                <el-tag :type="overallLevelType(row.overallLevel)" size="small">
                  {{ overallLevelLabel(row.overallLevel) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="conclusion" label="结论摘要" min-width="220" show-overflow-tooltip />
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="viewHistoryReport(row)">查看</el-button>
                <el-button link type="primary" size="small" @click="exportHistoryReport(row, 'pdf')">PDF</el-button>
                <el-button link type="primary" size="small" @click="exportHistoryReport(row, 'word')">Word</el-button>
                <el-button link type="danger" size="small" @click="deleteHistoryReport(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
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
                  <span class="resource-icon-wrap" :class="`resource-icon-wrap--${item.key}`">
                    <el-icon><component :is="item.icon" /></el-icon>
                  </span>
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

    <el-dialog v-model="generateDialogVisible" title="生成性能瓶颈诊断报告" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="导出格式（可选）">
          <el-radio-group v-model="generateExportFormat" class="export-format-group">
            <el-radio label="preview">仅生成预览</el-radio>
            <el-radio label="pdf">生成并导出 PDF</el-radio>
            <el-radio label="word">生成并导出 Word</el-radio>
            <el-radio label="both">生成并导出 PDF + Word</el-radio>
          </el-radio-group>
        </el-form-item>
        <p class="hint-inline">
          报告将基于当前慢查询、缓存命中、JVM 堆内存与 SQL 拦截数据实时生成，并自动加入下方报告记录。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="loadingReport" @click="confirmGenerateReport">开始生成</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Bell,
  Box,
  ChatDotRound,
  Coin,
  CopyDocument,
  Cpu,
  DataBoard,
  Document,
  Download,
  FolderOpened,
  InfoFilled,
  Lightning,
  Monitor,
  Odometer,
  Refresh,
  Search,
  SetUp,
  Share,
  Timer,
  TrendCharts,
  Upload
} from '@element-plus/icons-vue'
import {
  appendPerfReportHistory,
  buildPerfReportMarkdown,
  clearPerfReportHistory,
  createPerfReportPdfBlob,
  downloadBlob,
  downloadPerfReportPdf,
  exportPerfReportWord,
  loadPerfReportHistory,
  removePerfReportHistory,
  reportExportFilename
} from '../../utils/perfReportExport'
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
  { id: 'monitor', title: '实时监控', desc: 'JVM、磁盘与引擎概览', icon: Odometer },
  { id: 'dbPressure', title: '数据库压力', desc: '连接池与限流管控', icon: Coin },
  { id: 'slowQuery', title: '慢查询治理', desc: '识别、熔断与处置', icon: Timer, badge: slowCountBadge.value || null },
  { id: 'cache', title: '缓存优化', desc: '语义缓存与命中分析', icon: Lightning },
  { id: 'batch', title: '批处理优化', desc: '并发调度与预热', icon: Box },
  { id: 'bottleneck', title: '瓶颈分析', desc: '一键生成诊断报告', icon: TrendCharts },
  { id: 'alert', title: '性能告警', desc: '阈值与通知渠道', icon: Bell, badge: cpuAlertActive.value ? '!' : null },
  { id: 'resource', title: '资源调度', desc: '模块优先级分配', icon: SetUp }
])

const moduleHints = {
  monitor: {
    title: '实时监控',
    desc: '关注 JVM 堆、CPU 负载与 SQL 审计指标。点击指标卡可跳转到对应治理模块。'
  },
  dbPressure: {
    title: '数据库压力管控',
    desc: '通过连接池上限与单用户限流，避免高频查询拖垮数据库。右侧表格展示实时限流状态。'
  },
  slowQuery: {
    title: '慢查询治理',
    desc: '配置慢查询阈值与熔断策略，对异常 SQL 进行标记处置或终止。支持按用户、表名快速筛选。'
  },
  cache: {
    title: '缓存优化',
    desc: '管理 Redis 语义缓存 TTL 与开关，查看命中明细并在必要时清理无效条目。'
  },
  batch: {
    title: '批处理优化',
    desc: '控制批处理并发与超时，配置看板预热 Cron，降低与用户查询的资源争抢。'
  },
  bottleneck: {
    title: '瓶颈分析',
    desc: '聚合慢查询、缓存、堆内存等信号，生成可导出 PDF / Word 的正式诊断报告。'
  },
  alert: {
    title: '性能告警',
    desc: '设置 CPU、慢查询与响应超时阈值。通知渠道在全局系统参数中配置。'
  },
  resource: {
    title: '资源调度',
    desc: '为 Text-to-SQL、GraphRAG 等模块分配 0–100 优先级，数值越高越优先保障资源。'
  }
}

const activeTab = ref('monitor')
const loading = ref(false)
const loadingReport = ref(false)
const exportingPdf = ref(false)
const exportingWord = ref(false)
const loadingReportPreview = ref(false)
const generateDialogVisible = ref(false)
const generateExportFormat = ref('preview')
const reportHistory = ref(loadPerfReportHistory())
const reportPdfPreviewUrl = ref('')
const reportPreviewRef = ref(null)
let activeReportPdfBlob = null
const clearingCache = ref(false)
const autoRefresh = ref(false)
const lastRefresh = ref('')
const slowQueryFilter = ref('')
let refreshTimer = null

const overview = ref(null)
const slowQueries = ref([])
const batchTasks = ref([])
const interventions = ref([])
const cacheEntries = ref([])
const bottleneckReport = ref(null)

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
  { key: 'text2sql', label: 'Text-to-SQL', icon: ChatDotRound, desc: '自然语言转 SQL 核心引擎' },
  { key: 'graphrag', label: 'GraphRAG', icon: Share, desc: '知识图谱检索增强推理' },
  { key: 'upload', label: '上传批处理', icon: Upload, desc: 'Excel / 文件导入批处理任务' },
  { key: 'dashboard', label: '看板渲染', icon: DataBoard, desc: '看板组件查询与预热渲染' }
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
  const jvm = overview.value?.jvm || {}
  if (jvm.cpuUsagePercent != null) {
    return Math.min(100, Math.round(Number(jvm.cpuUsagePercent)))
  }
  const load = jvm.systemLoadAverage
  const cpus = jvm.processors
  if (load == null || !cpus) return null
  return Math.min(100, Math.round((load / cpus) * 100))
})

const cpuLoadSubText = computed(() => {
  const jvm = overview.value?.jvm || {}
  const cpus = jvm.processors ?? '-'
  if (jvm.systemLoadAverage != null) {
    return `核数 ${cpus} · 1m 负载 ${Number(jvm.systemLoadAverage).toFixed(2)}`
  }
  if (cpuUsagePercent.value != null) {
    return `核数 ${cpus} · 系统 CPU 占用`
  }
  return `核数 ${cpus} · CPU 数据暂不可用`
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

const platformHealthScore = computed(() => {
  const heap = Number(overview.value?.jvm?.heapUsedPercent) || 0
  const slow = Number(overview.value?.sqlAudit?.slowCount) || 0
  const blocked = Number(overview.value?.sqlAudit?.blockedCount) || 0
  const score = 100
    - Math.min(25, heap * 0.2)
    - Math.min(20, slow * 2)
    - Math.min(15, blocked * 3)
    - (cpuAlertActive.value ? 10 : 0)
  return `${Math.max(40, Math.round(score))} / 100`
})

const healthScoreTone = computed(() => {
  const score = Number(platformHealthScore.value.split('/')[0]?.trim()) || 0
  if (score >= 80) return 'is-good'
  if (score >= 60) return 'is-warn'
  return 'is-bad'
})

const activeModuleHint = computed(() => moduleHints[activeTab.value] || null)

const filteredSlowQueries = computed(() => {
  const q = slowQueryFilter.value.trim().toLowerCase()
  if (!q) return slowQueries.value
  return slowQueries.value.filter((row) =>
    [row.userId, row.tableName, row.executeStatus, String(row.id)].some((v) =>
      String(v || '').toLowerCase().includes(q)
    )
  )
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
      icon: Cpu,
      title: 'JVM 堆内存',
      value: formatBytes(jvm.heapUsedBytes),
      sub: jvm.heapMaxBytes ? `最大 ${formatBytes(jvm.heapMaxBytes)}` : '',
      percent: heapPct,
      alert: heapPct != null && heapPct >= 85,
      linkTab: 'alert'
    },
    {
      key: 'cpu',
      icon: Monitor,
      title: 'CPU 负载',
      value: cpuPct != null ? `${cpuPct}%` : '—',
      sub: cpuLoadSubText.value,
      percent: cpuPct,
      alert: cpuAlertActive.value,
      linkTab: 'alert'
    },
    {
      key: 'disk',
      icon: FolderOpened,
      title: '磁盘占用',
      value: disk.usedPercent != null ? `${disk.usedPercent}%` : '—',
      sub: disk.usedBytes != null ? `${formatBytes(disk.usedBytes)} / ${formatBytes(disk.totalBytes)}` : disk.path,
      percent: disk.usedPercent,
      alert: disk.usedPercent != null && disk.usedPercent >= 90
    },
    {
      key: 'cache',
      icon: Lightning,
      title: '语义缓存命中',
      value: `${cache.hitRate ?? 0}%`,
      sub: `条目 ${cache.cacheCount ?? 0} · Redis ${cache.redisStatus || 'LOCAL'}`,
      percent: cache.hitRate,
      status: cache.redisStatus || 'LOCAL',
      statusType: cache.redisStatus === 'UP' ? 'success' : 'info',
      linkTab: 'cache'
    },
    {
      key: 'sql',
      icon: Document,
      title: 'SQL 审计',
      value: `${sql.total ?? 0} 条`,
      sub: `慢 ${sql.slowCount ?? 0} · 拦截 ${sql.blockedCount ?? 0} · 均 ${sql.avgDurationMs ?? 0}ms`,
      percent: sql.slowCount > 0 ? Math.min(100, sql.slowCount) : 0,
      alert: (sql.slowCount ?? 0) > 20,
      linkTab: 'slowQuery'
    }
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

const reportHelpers = () => ({
  overallLevelLabel,
  formatReportTime
})

const revokeReportPdfPreview = () => {
  if (reportPdfPreviewUrl.value) {
    URL.revokeObjectURL(reportPdfPreviewUrl.value)
    reportPdfPreviewUrl.value = ''
  }
  activeReportPdfBlob = null
}

const showReportPdfPreview = async (report) => {
  if (!report) return
  loadingReportPreview.value = true
  revokeReportPdfPreview()
  bottleneckReport.value = report
  try {
    activeReportPdfBlob = await createPerfReportPdfBlob(report, reportHelpers())
    reportPdfPreviewUrl.value = URL.createObjectURL(activeReportPdfBlob)
  } finally {
    loadingReportPreview.value = false
  }
}

const openGenerateDialog = () => {
  generateExportFormat.value = 'preview'
  generateDialogVisible.value = true
}

const persistReport = (report) => {
  reportHistory.value = appendPerfReportHistory(report)
}

const exportReportByFormat = async (report, format) => {
  if (!report) return
  const helpers = reportHelpers()
  if (format === 'word' || format === 'both') {
    exportingWord.value = true
    try {
      exportPerfReportWord(report, reportExportFilename(report, 'doc'), helpers)
    } finally {
      exportingWord.value = false
    }
  }
  if (format === 'pdf' || format === 'both') {
    exportingPdf.value = true
    try {
      if (bottleneckReport.value?.reportId === report.reportId && activeReportPdfBlob) {
        downloadBlob(activeReportPdfBlob, reportExportFilename(report, 'pdf'))
      } else {
        await downloadPerfReportPdf(report, reportExportFilename(report, 'pdf'), helpers)
      }
    } finally {
      exportingPdf.value = false
    }
  }
}

const confirmGenerateReport = async () => {
  loadingReport.value = true
  try {
    const report = await fetchPerfBottleneckReport()
    persistReport(report)
    generateDialogVisible.value = false
    await showReportPdfPreview(report)
    const format = generateExportFormat.value
    if (format !== 'preview') {
      await exportReportByFormat(report, format)
    }
    ElMessage.success(
      format === 'preview' ? '诊断报告已生成' : `诊断报告已生成并完成${format === 'both' ? ' PDF / Word' : format.toUpperCase()} 导出`
    )
    await nextTick()
    reportPreviewRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  } catch (e) {
    ElMessage.error(e.message || '报告生成失败')
  } finally {
    loadingReport.value = false
  }
}

const exportCurrentReport = async (format) => {
  if (!bottleneckReport.value) return
  try {
    await exportReportByFormat(bottleneckReport.value, format)
    ElMessage.success(`${format.toUpperCase()} 导出成功`)
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

const viewHistoryReport = async (row) => {
  if (!row?.report) return
  try {
    await showReportPdfPreview(row.report)
    await nextTick()
    reportPreviewRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  } catch (e) {
    ElMessage.error(e.message || '报告预览加载失败')
  }
}

const exportHistoryReport = async (row, format) => {
  if (!row?.report) return
  try {
    await exportReportByFormat(row.report, format)
    ElMessage.success(`${format.toUpperCase()} 导出成功`)
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

const deleteHistoryReport = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除报告 ${row.id} 吗？`, '删除报告记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  reportHistory.value = removePerfReportHistory(row.id)
  if (bottleneckReport.value?.reportId === row.id) {
    bottleneckReport.value = null
    revokeReportPdfPreview()
  }
  ElMessage.success('报告记录已删除')
}

const clearReportHistory = async () => {
  try {
    await ElMessageBox.confirm('确定清空全部报告记录吗？此操作不可恢复。', '清空报告记录', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  reportHistory.value = clearPerfReportHistory()
  bottleneckReport.value = null
  revokeReportPdfPreview()
  ElMessage.success('报告记录已清空')
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

const buildReportMarkdown = () => buildPerfReportMarkdown(bottleneckReport.value, reportHelpers())

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

const goTab = (tab) => {
  activeTab.value = tab
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
  revokeReportPdfPreview()
})
</script>

<style scoped>
.perf-center {
  padding: 0 4px 28px;
}

.perf-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 20px;
  padding: 24px 26px;
  margin-bottom: 18px;
  border-radius: 24px;
  border: 1px solid #e8edf7;
  background: linear-gradient(135deg, #f8fbff 0%, #ffffff 55%, #f5f3ff 100%);
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.06);
}

.perf-hero-tag {
  margin-bottom: 4px;
}

.perf-hero-main h1 {
  margin: 10px 0 8px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #0f172a;
}

.perf-hero-main p {
  margin: 0;
  max-width: 720px;
  color: #526179;
  font-size: 14px;
  line-height: 1.7;
}

.perf-hero-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
}

.perf-hero-score {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 22px;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.7);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(12px);
  box-shadow: 0 16px 36px rgba(37, 99, 235, 0.1);
}

.perf-hero-score span,
.perf-hero-score small {
  color: #64748b;
}

.perf-hero-score strong {
  margin: 8px 0;
  font-size: 40px;
  line-height: 1;
  color: #2563eb;
}

.perf-hero-score.is-warn strong {
  color: #d97706;
}

.perf-hero-score.is-bad strong {
  color: #dc2626;
}

.module-nav {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.module-nav-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 14px 14px 14px 12px;
  border: 1px solid #e8edf7;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.module-nav-item:hover {
  transform: translateY(-2px);
  border-color: #bfdbfe;
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.08);
}

.module-nav-item.active {
  border-color: #93c5fd;
  background: linear-gradient(180deg, #eff6ff, #ffffff);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.12);
}

.module-nav-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  flex-shrink: 0;
  color: #2563eb;
  background: #eff6ff;
}

.module-nav-icon--slowQuery { color: #d97706; background: #fffbeb; }
.module-nav-icon--alert { color: #dc2626; background: #fff1f2; }
.module-nav-icon--cache { color: #7c3aed; background: #f5f3ff; }
.module-nav-icon--batch { color: #059669; background: #ecfdf5; }
.module-nav-icon--bottleneck { color: #0891b2; background: #ecfeff; }
.module-nav-icon--resource { color: #475569; background: #f8fafc; }

.module-nav-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.module-nav-text strong {
  font-size: 13px;
  color: #172033;
}

.module-nav-text small {
  color: #94a3b8;
  font-size: 11px;
  line-height: 1.4;
}

.module-nav-badge {
  position: absolute;
  top: 8px;
  right: 8px;
}

.module-hint {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  border-radius: 16px;
  border: 1px solid #dbeafe;
  background: linear-gradient(135deg, #f8fbff, #ffffff);
}

.module-hint-icon {
  margin-top: 2px;
  color: #2563eb;
  font-size: 18px;
}

.module-hint-body strong {
  display: block;
  margin-bottom: 4px;
  color: #172033;
  font-size: 14px;
}

.module-hint-body p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.55;
}

.auto-switch {
  margin-right: 4px;
}

.module-pane {
  animation: fadeIn 0.22s ease;
}

@keyframes fadeIn {
  from { opacity: 0.6; transform: translateY(6px); }
  to { opacity: 1; transform: none; }
}

.mt8 { margin-top: 8px; }
.mt16 { margin-top: 16px; }
.mt24 { margin-top: 24px; }
.mb16 { margin-bottom: 16px; }

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  margin-bottom: 0;
  min-height: 148px;
  border-radius: 18px;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.metric-card.clickable {
  cursor: pointer;
}

.metric-card.clickable:hover {
  transform: translateY(-3px);
  box-shadow: 0 16px 30px rgba(37, 99, 235, 0.1);
}

.metric-card.alert {
  border-color: #fca5a5;
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.12);
}

.metric-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.metric-icon-wrap {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  font-size: 18px;
}

.metric-icon-wrap--heap { color: #7c3aed; background: #f5f3ff; }
.metric-icon-wrap--cpu { color: #2563eb; background: #eff6ff; }
.metric-icon-wrap--disk { color: #0891b2; background: #ecfeff; }
.metric-icon-wrap--cache { color: #d97706; background: #fffbeb; }
.metric-icon-wrap--sql { color: #dc2626; background: #fff1f2; }

.metric-title {
  font-size: 13px;
  color: #64748b;
}

.metric-value {
  font-size: 24px;
  font-weight: 700;
  margin: 4px 0 8px;
  color: #0f172a;
}

.metric-sub {
  margin: 8px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.metric-link-hint {
  display: inline-block;
  margin-top: 8px;
  font-size: 12px;
  color: #2563eb;
  font-weight: 600;
}

.metric-bar {
  margin-top: 4px;
}

.panel-card {
  margin-bottom: 16px;
  border-radius: 18px;
}

.config-card :deep(.el-form-item) {
  margin-bottom: 18px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.card-head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-filter {
  width: 220px;
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
  padding: 10px 0;
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
  color: #64748b;
  font-size: 12px;
  line-height: 1.55;
  margin: 8px 0 0;
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
  flex-wrap: wrap;
  gap: 12px;
}

.export-format-group {
  display: grid;
  gap: 10px;
}

.report-history-card :deep(.el-empty) {
  padding: 24px 0;
}

.guided-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 220px;
  padding: 28px;
  border-radius: 20px;
  border: 1px dashed #bfdbfe;
  background: linear-gradient(180deg, #f8fbff, #ffffff);
  text-align: center;
}

.guided-empty-icon {
  font-size: 42px;
  color: #93c5fd;
}

.guided-empty strong {
  font-size: 16px;
  color: #172033;
}

.guided-empty p {
  margin: 0 0 8px;
  max-width: 420px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.report-preview-loading {
  min-height: 520px;
  border-radius: 18px;
  border: 1px solid #e8edf7;
  background: #fff;
}

.report-preview-placeholder {
  min-height: 520px;
}

.report-pdf-preview {
  border: 1px solid #dcdfe6;
  border-radius: 18px;
  background: #f8fafc;
  overflow: hidden;
}

.report-preview-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 18px;
  background: linear-gradient(180deg, #fafcff 0%, #fff 100%);
  border-bottom: 1px solid #e8edf7;
}

.report-preview-head strong {
  display: block;
  font-size: 16px;
  color: #172033;
}

.report-meta {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.report-pdf-frame {
  display: block;
  width: 100%;
  min-height: 720px;
  height: 78vh;
  border: 0;
  background: #525659;
}

.channel-chips {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.resource-row {
  margin-bottom: 20px;
  padding: 14px;
  border-radius: 14px;
  border: 1px solid #edf2f7;
  background: #fbfdff;
}

.resource-label {
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
}

.resource-icon-wrap {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  font-size: 20px;
  flex-shrink: 0;
}

.resource-icon-wrap--text2sql { color: #2563eb; background: #eff6ff; }
.resource-icon-wrap--graphrag { color: #059669; background: #ecfdf5; }
.resource-icon-wrap--upload { color: #d97706; background: #fffbeb; }
.resource-icon-wrap--dashboard { color: #7c3aed; background: #f5f3ff; }

.resource-label p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #94a3b8;
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

:deep(.el-table tbody tr:hover > td) {
  background-color: #f8fbff !important;
}

@media (max-width: 1200px) {
  .perf-hero {
    grid-template-columns: 1fr;
  }

  .module-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-grid {
    grid-template-columns: repeat(5, minmax(168px, 1fr));
    overflow-x: auto;
    padding-bottom: 4px;
  }
}

@media (max-width: 720px) {
  .perf-hero {
    padding: 20px;
    border-radius: 20px;
  }

  .perf-hero-main h1 {
    font-size: 24px;
  }

  .module-nav {
    grid-template-columns: 1fr;
  }

  .card-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .card-head-tools {
    width: 100%;
  }

  .table-filter {
    flex: 1;
    width: auto;
  }
}
</style>
