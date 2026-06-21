<template>
  <section class="admin-chat-history">
    <header class="history-hero">
      <div class="history-title-block">
        <h1>管理员对话历史</h1>
        <p>{{ heroDescription }}</p>
      </div>
    </header>

    <div class="metric-grid">
      <article class="metric-card metric-card--blue">
        <span class="metric-icon">Q</span>
        <div class="metric-content">
          <span>总计记录</span>
          <strong>{{ summary.total || 0 }}</strong>
          <small>成功率 {{ formatPercent(summary.successRate) }}</small>
        </div>
      </article>
      <article class="metric-card metric-card--orange">
        <span class="metric-icon">D</span>
        <div class="metric-content">
          <span>今天查询</span>
          <strong>{{ summary.todayCount || 0 }}</strong>
          <small>拦截 {{ summary.blockedCount || 0 }} / 告警 {{ summary.warnCount || 0 }}</small>
        </div>
      </article>
      <article class="metric-card metric-card--purple">
        <span class="metric-icon">H</span>
        <div class="metric-content">
          <span>缓存命中率</span>
          <strong>{{ formatPercent(summary.cacheHitRate) }}</strong>
          <small>命中数 {{ summary.cacheHitCount || 0 }}</small>
        </div>
      </article>
      <article class="metric-card metric-card--red">
        <span class="metric-icon">S</span>
        <div class="metric-content">
          <span>慢查询</span>
          <strong>{{ summary.slowCount || 0 }}</strong>
          <small>平均耗时 {{ summary.avgDurationMs || 0 }} ms</small>
        </div>
      </article>
    </div>

    <div class="history-dashboard-grid">
      <main class="history-workspace">
        <section class="history-panel filter-panel">
          <div class="panel-head panel-head--compact">
            <div>
              <h2>对话列表</h2>
              <p>组合筛选用户、数据源、模型、风险和时间范围。</p>
            </div>
          </div>
          <div class="filter-grid">
            <label class="filter-field">
              <span>User</span>
              <el-input v-model.trim="filters.userId" placeholder="用户ID / 昵称" clearable @keyup.enter="loadList(1)" />
            </label>
            <label class="filter-field">
              <span>DataSource</span>
              <el-input v-model.trim="filters.tableName" placeholder="表名 / 数据源" clearable @keyup.enter="loadList(1)" />
            </label>
            <label class="filter-field">
              <span>类型</span>
              <el-select v-model="filters.sourceType" clearable placeholder="全部">
                <el-option label="上传数据" value="UPLOAD" />
                <el-option label="官方数据源" value="OFFICIAL" />
              </el-select>
            </label>
            <label class="filter-field">
              <span>模型</span>
              <el-input v-model.trim="filters.modelType" placeholder="模型类型" clearable @keyup.enter="loadList(1)" />
            </label>
            <label class="filter-field">
              <span>风险</span>
              <el-select v-model="filters.riskLevel" clearable placeholder="全部">
                <el-option label="安全" value="SAFE" />
                <el-option label="告警" value="WARN" />
                <el-option label="拦截" value="BLOCKED" />
              </el-select>
            </label>
            <label class="filter-field">
              <span>状态</span>
              <el-select v-model="filters.executionStatus" clearable placeholder="全部">
                <el-option label="成功" value="SUCCESS" />
                <el-option label="失败" value="FAILED" />
                <el-option label="取消" value="CANCELLED" />
              </el-select>
            </label>
            <label class="filter-field">
              <span>图表</span>
              <el-select v-model="filters.chartType" clearable placeholder="全部">
                <el-option label="柱状图" value="bar" />
                <el-option label="折线图" value="line" />
                <el-option label="饼图" value="pie" />
                <el-option label="表格" value="table" />
              </el-select>
            </label>
            <label class="filter-field">
              <span>缓存</span>
              <el-select v-model="filters.cacheHit" clearable placeholder="全部">
                <el-option label="命中" :value="true" />
                <el-option label="未命中" :value="false" />
              </el-select>
            </label>
            <label class="filter-field filter-field--wide">
              <span>关键词</span>
              <el-input v-model.trim="filters.keyword" placeholder="搜索问题 / SQL / 用户" clearable @keyup.enter="loadList(1)" />
            </label>
            <label class="filter-field">
              <span>慢查询</span>
              <el-select v-model="filters.slowQuery" clearable placeholder="全部">
                <el-option label="是" :value="true" />
                <el-option label="否" :value="false" />
              </el-select>
            </label>
            <label class="filter-field filter-field--date">
              <span>Time range</span>
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                unlink-panels
              />
            </label>
            <div class="filter-actions">
              <el-button @click="resetFilters">重置</el-button>
              <el-button type="primary" :loading="loading" @click="loadList(1)">查询</el-button>
            </div>
          </div>
        </section>

        <section class="history-panel table-panel">
          <div class="panel-head panel-head--table">
            <div>
              <h2>历史记录</h2>
              <p>共 {{ total }} 条，当前页 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</p>
            </div>
            <div class="panel-actions">
              <el-button @click="toggleSort">{{ filters.sortDirection === 'DESC' ? '时间倒序' : '时间正序' }}</el-button>
              <el-button :loading="loading" @click="loadList(page)">刷新</el-button>
              <el-button :loading="exporting" @click="exportExcel">导出 Excel</el-button>
              <el-button type="danger" :disabled="!selectedIds.length" @click="deleteSelected">
                {{ selectedIds.length ? `删除 ${selectedIds.length} 条` : '批量删除' }}
              </el-button>
            </div>
          </div>

          <el-table
            v-loading="loading"
            :data="rows"
            class="history-table"
            @selection-change="handleSelectionChange"
            @row-click="openDetail"
          >
            <el-table-column type="selection" width="34" />
            <el-table-column prop="createdAt" label="时间" width="118" />
            <el-table-column label="用户" width="96">
              <template #default="{ row }">
                <div class="user-cell">
                  <strong>{{ row.operatorLabel || row.userId }}</strong>
                  <small>{{ row.userId }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="查询内容" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="query-cell">
                  <strong>{{ row.question || '未记录问题' }}</strong>
                  <small>{{ historyResultTypeLabel(row) }} / {{ row.executionTimeMs ?? '-' }} ms / {{ row.isHitCacheLabel || '缓存未知' }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="queryTableName" label="DataSource" min-width="112" show-overflow-tooltip />
            <el-table-column label="模型" width="88" show-overflow-tooltip>
              <template #default="{ row }">
                {{ displayModelCategory(row) }}
              </template>
            </el-table-column>
            <el-table-column label="SQL 状态" width="88">
              <template #default="{ row }">
                <el-tag size="small" :type="sqlStatusTagType(row.sqlStatus)">{{ row.sqlStatusLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="智能分析" width="92">
              <template #default="{ row }">
                <el-tag size="small" :type="aiParseTagType(row.aiParseResult)">{{ aiParseResultText(row.aiParseResult, row.aiParseResultLabel) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="风险" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="riskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="118" fixed="right" align="center">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-tooltip content="查看详情" placement="top">
                    <el-button class="table-action-btn" circle :icon="View" @click.stop="openDetail(row)" />
                  </el-tooltip>
                  <el-tooltip content="重新执行" placement="top">
                    <el-button class="table-action-btn" circle type="primary" :icon="RefreshRight" @click.stop="rerun(row)" />
                  </el-tooltip>
                  <el-tooltip content="删除记录" placement="top">
                    <el-button class="table-action-btn" circle type="danger" :icon="Delete" @click.stop="deleteOne(row)" />
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <div class="table-footer">
            <span>已选 {{ selectedIds.length }} 条</span>
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              :total="total"
              @current-change="loadList"
              @size-change="handlePageSizeChange"
            />
          </div>
        </section>
      </main>

      <aside class="history-side-rail">
        <section class="history-panel side-panel">
          <div class="panel-head panel-head--compact">
            <div>
              <h2>性能追踪</h2>
              <p>慢查询、缓存与风险概览</p>
            </div>
          </div>
          <div class="side-stat-list">
            <div class="side-stat">
              <span>慢查询率</span>
              <strong>{{ formatPercent(analytics.performance?.summary?.slowQueryRate) }}</strong>
            </div>
            <div class="side-stat">
              <span>缓存命中率</span>
              <strong>{{ formatPercent(analytics.performance?.summary?.cacheHitRate) }}</strong>
            </div>
            <div class="side-stat">
              <span>平均耗时</span>
              <strong>{{ analytics.performance?.summary?.avgDurationMs || 0 }} ms</strong>
            </div>
            <div class="side-stat">
              <span>风险查询</span>
              <strong>{{ analytics.performance?.summary?.riskCount || 0 }}</strong>
            </div>
          </div>
        </section>

        <section class="history-panel side-panel route-audit-panel">
          <div class="panel-head panel-head--compact">
            <div>
              <h2>智能路由观测</h2>
              <p>低置信度、兜底、澄清与执行器健康情况</p>
            </div>
          </div>
          <template v-if="hasRouteAuditData">
            <div class="route-audit-stat-grid">
              <div
                v-for="card in routeAuditOverviewCards"
                :key="card.label"
                class="route-audit-stat"
                :class="`route-audit-stat--${card.tone}`"
              >
                <span>{{ card.label }}</span>
                <strong>{{ card.value }}</strong>
                <small>{{ card.hint }}</small>
              </div>
            </div>

            <div class="route-audit-section" v-if="routeIntentGroups.length">
              <div class="route-audit-section-head">
                <span>高频意图</span>
                <small>成功 / 兜底</small>
              </div>
              <div class="route-audit-list">
                <div v-for="item in routeIntentGroups" :key="item.primaryIntent" class="route-audit-row">
                  <div class="route-audit-main">
                    <strong>{{ routeAuditIntentLabel(item.primaryIntent) }}</strong>
                    <small>{{ item.count || 0 }} 次 · 平均置信度 {{ formatRouteConfidence(item.avgConfidence) }}</small>
                  </div>
                  <div class="route-audit-meta">
                    <span>{{ formatPercent(item.successRate) }}</span>
                    <strong>{{ formatPercent(item.fallbackRate) }}</strong>
                  </div>
                </div>
              </div>
            </div>

            <div class="route-audit-section" v-if="routeExecutorGroups.length">
              <div class="route-audit-section-head">
                <span>执行器耗时</span>
                <small>平均耗时</small>
              </div>
              <div class="route-audit-list">
                <div v-for="item in routeExecutorGroups" :key="item.chosenExecutor" class="route-audit-row">
                  <div class="route-audit-main">
                    <strong>{{ routeAuditExecutorLabel(item.chosenExecutor) }}</strong>
                    <small>{{ item.count || 0 }} 次 · 失败率 {{ formatPercent(item.failureRate) }}</small>
                  </div>
                  <div class="route-audit-meta">
                    <strong>{{ item.avgDurationMs || 0 }} ms</strong>
                  </div>
                </div>
              </div>
            </div>

            <div class="route-audit-section" v-if="routeProblemSamples.length">
              <div class="route-audit-section-head">
                <span>排查样本</span>
                <small>失败 / 兜底 / 低置信度</small>
              </div>
              <div class="route-audit-list route-audit-list--samples">
                <div v-for="item in routeProblemSamples" :key="item.historyId || item.question" class="route-audit-sample">
                  <strong>{{ item.question || '未记录问题' }}</strong>
                  <small>{{ formatRouteProblemHint(item) }}</small>
                  <div class="route-audit-tags">
                    <el-tag size="small" effect="plain">{{ routeAuditIntentLabel(item.primaryIntent) }}</el-tag>
                    <el-tag size="small" :type="routeOutcomeTagType(item.outcome)" effect="light">
                      {{ routeAuditOutcomeLabel(item.outcome) }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <p v-else class="detail-empty">暂无智能路由观测样本。</p>
        </section>

        <section class="history-panel side-panel">
          <div class="panel-head panel-head--compact">
            <div>
              <h2>SQL 慢查询</h2>
              <p>按问题聚合的高耗时记录</p>
            </div>
          </div>
          <div class="hot-list hot-list--side" v-if="sideSlowQueries.length">
            <div v-for="(item, index) in sideSlowQueries" :key="`slow-side-${index}`" class="hot-list-item">
              <div class="hot-list-main">
                <strong>{{ item.question || '未记录问题' }}</strong>
                <small>{{ item.queryTableName || '未指定数据源' }}</small>
              </div>
              <div class="hot-list-meta">
                <span>{{ item.hitCount || 0 }} 次</span>
                <strong>{{ item.avgDurationMs || item.executionTimeMs || 0 }} ms</strong>
              </div>
            </div>
          </div>
          <p v-else class="detail-empty">暂无慢查询热点。</p>
        </section>

        <section class="history-panel side-panel">
          <div class="panel-head panel-head--compact">
            <div>
              <h2>缓存命中热点</h2>
              <p>用于观察语义缓存策略</p>
            </div>
          </div>
          <div class="hot-list hot-list--side" v-if="sideCacheMissGroups.length">
            <div v-for="(item, index) in sideCacheMissGroups" :key="`cache-side-${index}`" class="hot-list-item">
              <div class="hot-list-main">
                <strong>{{ item.question || '未记录问题' }}</strong>
                <small>{{ item.queryTableName || '未指定数据源' }}</small>
              </div>
              <div class="hot-list-meta">
                <span>{{ item.missCount || item.hitCount || 0 }} 次</span>
                <strong>{{ item.avgDurationMs || 0 }} ms</strong>
              </div>
            </div>
          </div>
          <p v-else class="detail-empty">暂无缓存未命中热点。</p>
        </section>
      </aside>
    </div>

    <section class="history-panel trend-panel">
      <div class="panel-head panel-head--compact">
        <div>
          <h2>趋势分析</h2>
          <p>按当前筛选范围观察查询量、风险、缓存命中和慢查询变化。</p>
        </div>
      </div>
      <div class="analysis-grid">
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>查询趋势</h3>
              <p>近 30 个统计日的查询总量</p>
            </div>
          </div>
          <LegacyInlineChart title="" chart-type="line" :data="queryTrendPoints" />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>风险趋势</h3>
              <p>按天观察拦截与告警累计变化</p>
            </div>
          </div>
          <LegacyInlineChart title="" chart-type="bar" :data="riskTrendPoints" />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>缓存命中趋势</h3>
              <p>按天查看缓存命中次数变化</p>
            </div>
          </div>
          <LegacyInlineChart title="" chart-type="line" :data="cacheTrendPoints" />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>慢查询趋势</h3>
              <p>按天回看慢查询波动</p>
            </div>
          </div>
          <LegacyInlineChart title="" chart-type="bar" :data="slowTrendPoints" />
        </article>
      </div>
    </section>

    <el-drawer
      v-model="detailVisible"
      title="对话详情"
      size="82%"
      destroy-on-close
      append-to-body
      class="history-detail-drawer"
    >
      <div class="detail-scroll">
        <div v-if="detail" class="detail-wrap detail-wrap--drawer">
          <div class="detail-layout">
            <aside class="detail-sidebar">
              <section class="detail-overview">
                <div class="detail-panel detail-panel--muted detail-overview-shell">
                  <div class="detail-overview-main">
                    <div class="detail-badges" aria-label="当前记录状态">
                      <el-tag :type="statusTagType(detail.executionStatus)">{{ detail.executionStatusLabel }}</el-tag>
                      <el-tag :type="riskTagType(detail.riskLevel)">{{ detail.riskLevel }}</el-tag>
                      <el-tag type="info">{{ sourceTypeLabel(detail.sourceType) }}</el-tag>
                      <el-tag type="info">{{ historyResultTypeLabel(detail) }}</el-tag>
                      <el-tag v-if="shouldShowDetailSql" :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                      <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ aiParseResultText(detail.aiParseResult, detail.aiParseResultLabel) }}</el-tag>
                      <el-tag :type="detail.isHitCache ? 'success' : 'info'">{{ detail.isHitCacheLabel || '缓存未知' }}</el-tag>
                      <el-tag :type="detail.slowQuery ? 'warning' : 'info'">{{ detail.slowQuery ? '慢查询' : '非慢查询' }}</el-tag>
                    </div>
                    <div class="detail-question-card">
                      <span>原始问题</span>
                      <h2>{{ detail.question || '未记录原始问题' }}</h2>
                      <p>{{ detailHeroSummary }}</p>
                    </div>
                  </div>
                  <div class="detail-overview-grid detail-overview-grid--compact">
                    <div class="detail-overview-group">
                      <div class="detail-overview-group-title">基础信息</div>
                      <div class="detail-overview-list">
                        <div class="detail-overview-row">
                          <span>查询用户</span>
                          <div class="detail-overview-value">
                            <strong>{{ detail.operator?.displayName || detail.operatorLabel || detail.userId }}</strong>
                            <small>{{ detail.userId }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>发生时间</span>
                          <div class="detail-overview-value">
                            <strong>{{ detail.createdAt || '-' }}</strong>
                            <small>{{ detailTurnNoText }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>数据源</span>
                          <div class="detail-overview-value">
                            <strong>{{ detail.queryTableName || '-' }}</strong>
                            <small>{{ sourceTypeLabel(detail.sourceType) }}</small>
                          </div>
                        </div>
                      </div>
                    </div>

                    <div class="detail-overview-group">
                      <div class="detail-overview-group-title">执行信息</div>
                      <div class="detail-overview-list">
                        <div class="detail-overview-row">
                          <span>模型类型</span>
                          <div class="detail-overview-value">
                            <strong>{{ detailModelTypeText }}</strong>
                            <small>{{ detail.artifactType || 'CHART' }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>解析引擎</span>
                          <div class="detail-overview-value">
                            <strong>{{ detailParseEngineText }}</strong>
                            <small>{{ aiParseResultText(detail.aiParseResult, detail.aiParseResultLabel) }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>图表类型</span>
                          <div class="detail-overview-value">
                            <strong>{{ historyResultTypeLabel(detail) }}</strong>
                            <small>{{ intentTypeLabel(detail.intentType) }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>执行耗时</span>
                          <div class="detail-overview-value">
                            <strong>{{ formatDuration(detail.executionTimeMs) }}</strong>
                            <small>{{ detailConversationIdText }}</small>
                          </div>
                        </div>
                        <div v-if="shouldShowDetailSql" class="detail-overview-row">
                          <span>SQL 状态</span>
                          <div class="detail-overview-value">
                            <strong>{{ detail.sqlStatusLabel || '无 SQL' }}</strong>
                            <small>{{ detail.executionStatusLabel || '-' }}</small>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <section class="detail-section detail-panel" v-if="detail.auditSummary">
                <div class="detail-section-head">
                  <div class="detail-section-title">
                    <h3>安全审计摘要</h3>
                    <p>快速查看风险、拦截与慢查询情况</p>
                  </div>
                </div>
                <div class="audit-summary-grid">
                  <div
                    v-for="card in auditSummaryCards"
                    :key="card.label"
                    class="audit-summary-item"
                    :class="`audit-summary-item--${card.tone}`"
                  >
                    <div class="audit-summary-head">
                      <span>{{ card.label }}</span>
                      <em>{{ card.status }}</em>
                    </div>
                    <strong>{{ card.value }}</strong>
                    <p>{{ card.hint }}</p>
                  </div>
                </div>
              </section>

            </aside>

            <main class="detail-content">
              <el-tabs v-model="detailActiveTab" class="detail-tabs">
                <el-tab-pane label="查询与结果" name="query">
                  <div class="detail-tab-stack">
                    <section class="detail-section detail-panel detail-query-section">
                      <div class="detail-section-head detail-query-head">
                        <div class="detail-section-title">
                          <h3>查询内容</h3>
                          <p>{{ detailQueryContentSubtitle }}</p>
                        </div>
                        <div class="detail-actions detail-query-actions">
                          <el-button type="primary" :icon="RefreshRight" :loading="rerunning" @click="rerun(detail)">重新执行</el-button>
                          <el-button v-if="shouldShowDetailSql" :disabled="!detailRealSql" @click="copySql(detailRealSql)">复制 SQL</el-button>
                        </div>
                      </div>
                      <div class="detail-query-grid" :class="{ 'detail-query-grid--single': !shouldShowDetailSql }">
                        <article class="detail-subpanel detail-subpanel--question">
                          <div class="detail-subpanel-head">
                            <div class="detail-subpanel-title">
                              <span>原始问题</span>
                              <small>用户输入</small>
                            </div>
                            <el-tag size="small" effect="plain">{{ sourceTypeLabel(detail.sourceType) }}</el-tag>
                          </div>
                          <p class="detail-text detail-text--question">{{ detail.question || '未记录原始问题' }}</p>
                          <div class="detail-question-meta">
                            <span>{{ detail.createdAt || '时间未记录' }}</span>
                            <span>{{ detail.operator?.displayName || detail.operatorLabel || detail.userId || '用户未记录' }}</span>
                          </div>
                        </article>
                        <article v-if="shouldShowDetailSql" class="detail-subpanel detail-subpanel--code">
                          <div class="detail-subpanel-head">
                            <div class="detail-subpanel-title">
                              <span>生成 SQL</span>
                              <small>{{ detail.sqlStatusLabel || detail.executionStatusLabel || 'SQL 已记录' }}</small>
                            </div>
                            <el-tag size="small" effect="plain">{{ detailParseEngineText }}</el-tag>
                          </div>
                          <div class="detail-code-shell">
                            <pre class="detail-code">{{ detailSqlDisplayText }}</pre>
                          </div>
                        </article>
                      </div>
                    </section>

                    <section class="detail-section detail-panel detail-summary-section">
                      <div class="detail-section-head detail-summary-head">
                        <div class="detail-section-title">
                          <h3>对话摘要</h3>
                          <p>帮助管理员快速判断这次查询是如何被解析、生成和执行的</p>
                        </div>
                      </div>
                      <div class="context-grid detail-summary-grid">
                        <article class="context-card context-card--wide summary-card summary-card--primary">
                          <div class="context-card-head summary-card-head">
                            <div class="summary-card-title">
                              <span>摘要说明</span>
                              <small>本轮查询结论</small>
                            </div>
                            <el-tag effect="plain">{{ intentTypeLabel(detail.intentType) }}</el-tag>
                          </div>
                          <div class="summary-card-body">
                            <strong>{{ detailQuerySummaryText }}</strong>
                            <p>{{ detail.riskReason || '未记录额外执行说明。' }}</p>
                          </div>
                        </article>
                        <article class="context-card summary-card summary-card--parse">
                          <div class="context-card-head summary-card-head">
                            <div class="summary-card-title">
                              <span>解析结果</span>
                              <small>模型理解链路</small>
                            </div>
                            <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ aiParseResultText(detail.aiParseResult, detail.aiParseResultLabel) }}</el-tag>
                          </div>
                          <div class="summary-card-body">
                            <strong>{{ detailParseEngineText }}</strong>
                            <p>{{ detailParseDescription }}</p>
                          </div>
                        </article>
                        <article v-if="shouldShowDetailSql" class="context-card summary-card summary-card--sql">
                          <div class="context-card-head summary-card-head">
                            <div class="summary-card-title">
                              <span>SQL 生成状态</span>
                              <small>执行链路状态</small>
                            </div>
                            <el-tag :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                          </div>
                          <div class="summary-card-body">
                            <strong>{{ detail.executionStatusLabel || '-' }}</strong>
                            <p>{{ detailSqlStatusDescription }}</p>
                          </div>
                        </article>
                        <article v-if="detailAlertInfo" class="context-card context-card--wide summary-card summary-card--alert">
                          <div class="context-card-head summary-card-head">
                            <div class="summary-card-title">
                              <span>智能预警</span>
                              <small>规则、阈值与触发结果</small>
                            </div>
                            <el-tag :type="alertStatusTagType(detailAlertInfo.status)" effect="light">
                              {{ detailAlertInfo.statusLabel }}
                            </el-tag>
                          </div>
                          <div class="alert-summary-grid">
                            <div
                              v-for="item in detailAlertInfoItems"
                              :key="item.label"
                              class="alert-summary-item"
                              :class="{ 'alert-summary-item--wide': item.wide }"
                            >
                              <span>{{ item.label }}</span>
                              <strong>{{ item.value }}</strong>
                            </div>
                          </div>
                        </article>
                      </div>
                    </section>

                    <section v-if="detailHasResultPreview" class="detail-section detail-panel detail-result-section">
                      <div class="detail-section-head detail-result-head">
                        <div class="detail-section-title">
                          <h3>结果预览</h3>
                          <p>图表缩略图、结果概览与保留样例</p>
                        </div>
                      </div>
                      <div class="result-preview-stack">
                        <article class="context-card result-preview-summary-card">
                          <div class="context-card-head result-preview-card-head">
                            <div class="result-preview-card-title">
                              <span>结果概览</span>
                              <small>快照结构与数据来源</small>
                            </div>
                            <el-tag type="info" effect="plain">{{ detail.snapshotPreviewRows?.length || 0 }} 行样例</el-tag>
                          </div>
                          <div class="result-preview-summary-grid">
                            <div
                              v-for="item in resultPreviewMetrics"
                              :key="item.label"
                              class="result-preview-summary-item"
                            >
                              <span>{{ item.label }}</span>
                              <strong>{{ item.value }}</strong>
                              <small>{{ item.hint }}</small>
                            </div>
                          </div>
                        </article>

                        <article class="context-card result-preview-card result-preview-card--chart">
                          <div class="context-card-head result-preview-card-head">
                            <div class="result-preview-card-title">
                              <span>图表缩略图</span>
                              <small>保存的可视化结果</small>
                            </div>
                            <el-tag effect="plain">{{ chartTypeLabel(detail.chartType) }}</el-tag>
                          </div>
                          <div class="result-preview-chart-shell">
                            <DashboardChart
                              v-if="detailChartPayload"
                              :payload="detailChartPayload"
                              :display-title="detail.question || '图表结果'"
                              hide-title
                              class="result-preview-chart"
                            />
                            <p v-else class="detail-empty">当前记录没有可恢复的图表快照。</p>
                          </div>
                          <div v-if="detailFieldMappingSummary.length" class="trace-tags result-preview-field-tags">
                            <el-tag
                              v-for="item in detailFieldMappingSummary"
                              :key="`mapping-${item.label}`"
                              size="small"
                              effect="plain"
                            >
                              {{ item.label }}：{{ item.value }}
                            </el-tag>
                          </div>
                        </article>
                      </div>
                      <div v-if="detail.snapshotPreviewRows?.length" class="detail-table-wrap result-preview-table-card">
                        <div class="result-preview-table-head">
                          <div class="result-preview-card-title">
                            <span>结果样例</span>
                            <small>用于回溯本次图表生成的保留数据</small>
                          </div>
                          <el-tag effect="plain">{{ previewColumns.length }} 列</el-tag>
                        </div>
                        <el-table :data="detail.snapshotPreviewRows" size="small" border class="result-preview-table">
                          <el-table-column
                            v-for="column in previewColumns"
                            :key="column"
                            :prop="column"
                            :label="column"
                            min-width="120"
                            show-overflow-tooltip
                          >
                            <template #default="{ row }">
                              {{ formatPreviewValue(row[column]) }}
                            </template>
                          </el-table-column>
                        </el-table>
                      </div>
                      <p v-else class="detail-empty">当前记录没有保留结果表格样例。</p>
                    </section>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="权限与审计" name="audit">
                  <div class="detail-tab-stack">
                    <section class="detail-section detail-panel">
                      <div class="detail-section-head">
                        <div class="detail-section-title">
                          <h3>权限与缓存</h3>
                          <p>补充查看权限校验、缓存命中和脱敏处理等执行保护上下文</p>
                        </div>
                      </div>
                      <div class="audit-context-summary">
                        <div
                          v-for="card in auditContextOverviewCards"
                          :key="card.label"
                          class="reasoning-mini-item audit-context-summary-item"
                        >
                          <span>{{ card.label }}</span>
                          <strong>{{ card.value }}</strong>
                          <p>{{ card.hint }}</p>
                        </div>
                      </div>
                      <div class="context-grid audit-context-grid">
                        <article class="context-card audit-context-card audit-context-card--permission">
                          <div class="context-card-head audit-context-card-head">
                            <span>权限校验结果</span>
                            <el-tag :type="permissionStatusTagType(detail.permissionCheck?.status)">
                              {{ detail.permissionCheck?.label || '未记录' }}
                            </el-tag>
                          </div>
                          <strong>{{ detail.permissionCheck?.message || '未记录独立权限校验结果' }}</strong>
                          <p>{{ detail.permissionCheck?.detail || '当前详情仍沿用查询主链路的权限控制，但历史中没有保留更细粒度的校验快照。' }}</p>
                          <div class="context-meta audit-context-meta">
                            <span>授权作用域表</span>
                            <strong>{{ detail.permissionCheck?.scopeTableName || detail.queryTableName || '-' }}</strong>
                          </div>
                        </article>
                        <article class="context-card audit-context-card audit-context-card--cache">
                          <div class="context-card-head audit-context-card-head">
                            <span>缓存上下文</span>
                            <el-tag :type="detail.cacheContext?.cacheHit ? 'success' : 'info'">
                              {{ detail.cacheContext?.cacheHitLabel || '未命中缓存' }}
                            </el-tag>
                          </div>
                          <strong>{{ detail.cacheContext?.cacheAuditStatus || '未记录缓存审计结论' }}</strong>
                          <p>{{ detail.cacheContext?.redisStatusText || '当前审计日志未返回 Redis 侧状态。' }}</p>
                          <div class="context-meta audit-context-meta">
                            <span>缓存 Key</span>
                            <strong>{{ detail.cacheContext?.cacheKey || '未记录缓存 Key' }}</strong>
                          </div>
                          <div class="context-meta audit-context-meta">
                            <span>执行保护动作</span>
                            <strong>{{ detail.cacheContext?.queryGuardActionLabel || '未触发执行保护' }}</strong>
                          </div>
                        </article>
                        <article class="context-card context-card--wide audit-context-card audit-context-card--mask">
                          <div class="context-card-head audit-context-card-head">
                            <span>脱敏明细</span>
                            <el-tag type="warning" effect="plain">
                              {{ detail.latestAuditLog?.sensitiveFieldItems?.length ? `敏感字段 ${detail.latestAuditLog.sensitiveFieldItems.length}` : '未发现敏感字段' }}
                            </el-tag>
                          </div>
                          <strong>{{ detail.latestAuditLog?.maskDetail || '本次审计未返回脱敏明细，通常表示未命中敏感字段，或当前审计日志没有单独保存这部分文本。' }}</strong>
                          <div v-if="detail.latestAuditLog?.sensitiveFieldItems?.length" class="trace-tags">
                            <el-tag
                              v-for="(field, fieldIndex) in detail.latestAuditLog.sensitiveFieldItems"
                              :key="`latest-field-${fieldIndex}`"
                              size="small"
                              effect="plain"
                              type="warning"
                            >
                              {{ field }}
                            </el-tag>
                          </div>
                        </article>
                        <article v-if="shouldShowDetailSql && detail.cacheContext?.cacheSql" class="context-card context-card--wide context-card--code audit-context-card audit-context-card--sql">
                          <div class="context-card-head audit-context-card-head">
                            <span>缓存复用 SQL</span>
                            <el-tag effect="plain">缓存回放</el-tag>
                          </div>
                          <div class="audit-sql-preview">
                            <pre class="detail-code detail-code--light">{{ detail.cacheContext.cacheSql }}</pre>
                          </div>
                        </article>
                      </div>
                    </section>

                    <section v-if="shouldShowDetailSql && detail.auditLogs?.length" class="detail-section detail-panel">
                      <div class="detail-section-head">
                        <div class="detail-section-title">
                          <h3>SQL 审计日志</h3>
                          <p>命中规则、执行保护、生成轨迹与图谱匹配记录</p>
                        </div>
                      </div>
                      <div class="audit-log-list">
                        <div v-for="log in detail.auditLogs" :key="log.id" class="audit-log-card">
                          <div class="audit-log-summary">
                            <div class="audit-log-head">
                              <div class="audit-log-title">
                                <strong>{{ log.riskReason || '通过基础安全检测' }}</strong>
                                <span>{{ log.createdAt || '时间未记录' }}</span>
                              </div>
                              <div class="audit-log-tags">
                                <el-tag :type="riskTagType(log.riskLevel)" effect="plain">{{ log.riskLevel || 'SAFE' }}</el-tag>
                                <el-tag :type="statusTextType(log.executeStatus)" effect="plain">{{ formatTraceValue(log.executeStatus || 'SUCCESS') }}</el-tag>
                              </div>
                            </div>
                          </div>
                          <div class="audit-log-grid">
                            <div class="audit-log-metric">
                              <span>规则命中</span>
                              <div v-if="log.matchedRuleItems?.length" class="trace-tags">
                                <el-tag
                                  v-for="(rule, ruleIndex) in log.matchedRuleItems"
                                  :key="`${log.id || 'rule'}-${ruleIndex}`"
                                  size="small"
                                  effect="plain"
                                >
                                  {{ rule }}
                                </el-tag>
                              </div>
                              <strong v-else>未命中审计规则</strong>
                            </div>
                            <div class="audit-log-metric">
                              <span>敏感字段</span>
                              <div v-if="log.sensitiveFieldItems?.length" class="trace-tags">
                                <el-tag
                                  v-for="(field, fieldIndex) in log.sensitiveFieldItems"
                                  :key="`${log.id || 'field'}-${fieldIndex}`"
                                  size="small"
                                  effect="plain"
                                  type="warning"
                                >
                                  {{ field }}
                                </el-tag>
                              </div>
                              <strong v-else>未检测到敏感字段</strong>
                            </div>
                            <div class="audit-log-metric">
                              <span>缓存审计</span>
                              <strong>{{ log.cacheAuditStatus || '-' }}</strong>
                            </div>
                            <div class="audit-log-metric audit-log-metric--wide">
                              <span>执行保护</span>
                              <div v-if="log.executionGuardItems?.length" class="guard-summary">
                                <strong>{{ formatGuardSummary(log) }}</strong>
                                <div class="guard-detail-list">
                                  <div
                                    v-for="item in log.executionGuardItems"
                                    :key="item.id"
                                    class="guard-detail-item"
                                  >
                                    <span>{{ item.label }}</span>
                                    <strong>{{ item.value }}</strong>
                                  </div>
                                </div>
                              </div>
                              <strong v-else>{{ log.queryGuardActionLabel || '未触发执行保护' }}</strong>
                            </div>
                          </div>
                          <div class="trace-panel audit-trace-panel">
                            <div class="trace-panel-head">
                              <div>
                                <h4>SQL 生成轨迹</h4>
                                <small>缓存命中、SQL 生成引擎与执行结果</small>
                              </div>
                              <el-tag effect="plain">{{ log.generationTraceItems?.length || 0 }} 项</el-tag>
                            </div>
                            <div v-if="log.generationTraceItems?.length" class="trace-grid trace-grid--compact">
                              <div
                                v-for="item in log.generationTraceItems"
                                :key="item.id"
                                class="trace-item"
                                :class="{ 'trace-item--wide': item.wide }"
                              >
                                <span>{{ item.label }}</span>
                                <div v-if="item.tokens?.length" class="trace-tags">
                                  <el-tag
                                    v-for="(token, tokenIndex) in item.tokens"
                                    :key="`${item.id}-${tokenIndex}`"
                                    size="small"
                                    effect="plain"
                                  >
                                    {{ token }}
                                  </el-tag>
                                </div>
                                <ul v-else-if="item.valueLines?.length > 1" class="trace-value-list">
                                  <li v-for="(line, lineIndex) in item.valueLines" :key="`${item.id}-line-${lineIndex}`">
                                    {{ line }}
                                  </li>
                                </ul>
                                <strong v-else>{{ item.value }}</strong>
                              </div>
                            </div>
                            <p v-else class="detail-empty">无生成轨迹</p>
                          </div>

                        </div>
                      </div>
                    </section>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="推理与图谱" name="reasoning">
                  <div class="detail-tab-stack">
                    <div class="reasoning-graph-layout" v-if="reasoningDisplaySteps.length || detail.graphContext?.length">
                      <section class="detail-section detail-panel" v-if="reasoningDisplaySteps.length">
                        <div class="detail-section-head">
                          <div class="detail-section-title">
                            <h3>模型推理过程</h3>
                            <p>{{ reasoningIntroText }}</p>
                          </div>
                        </div>
                        <div class="reasoning-mini-summary">
                          <div
                            v-for="card in reasoningOverviewCards"
                            :key="card.label"
                            class="reasoning-mini-item"
                          >
                            <span>{{ card.label }}</span>
                            <strong>{{ card.value }}</strong>
                            <p v-if="card.hint">{{ card.hint }}</p>
                          </div>
                        </div>
                        <div class="reasoning-timeline-scroll">
                          <ol class="reasoning-timeline">
                            <li
                              v-for="(step, index) in reasoningDisplaySteps"
                              :key="step.id"
                              class="reasoning-timeline-item"
                            >
                              <div class="reasoning-timeline-marker">
                                <span class="reasoning-timeline-dot"></span>
                                <span v-if="index < reasoningDisplaySteps.length - 1" class="reasoning-timeline-line"></span>
                              </div>
                              <article class="reasoning-timeline-card">
                                <div class="reasoning-timeline-head">
                                  <span class="reasoning-stepno">第 {{ index + 1 }} 步</span>
                                  <strong>{{ step.title }}</strong>
                                  <el-tag size="small" :type="step.stage.type" effect="plain">{{ step.stage.label }}</el-tag>
                                </div>
                                <p>{{ step.mainDetail }}</p>
                                <ul v-if="step.extraDetails.length" class="reasoning-detail-list">
                                  <li v-for="(item, itemIndex) in step.extraDetails" :key="`${step.id}-${itemIndex}`">
                                    {{ item }}
                                  </li>
                                </ul>
                              </article>
                            </li>
                          </ol>
                        </div>
                      </section>

                      <section class="detail-section detail-panel" v-if="detail.graphContext?.length">
                        <div class="detail-section-head">
                          <div class="detail-section-title">
                            <h3>知识图谱上下文</h3>
                            <p>本次查询引用的图谱节点与字段内容</p>
                          </div>
                        </div>
                        <div v-if="graphVisualNodes.length" class="graph-visual">
                          <div class="graph-visual-head">
                            <strong>图谱关系</strong>
                            <div class="graph-visual-actions">
                              <span>{{ graphVisualNodes.length }} 个节点 · {{ graphVisualEdges.length }} 条关系</span>
                              <el-button size="small" text type="primary" @click="openFullGraphDialog">查看完整图谱关系</el-button>
                            </div>
                          </div>
                          <svg class="graph-visual-canvas" viewBox="0 0 720 360" role="img" aria-label="知识图谱节点关系图">
                            <defs>
                              <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                                <path d="M0,0 L8,4 L0,8 Z" fill="#93a4bc" />
                              </marker>
                            </defs>
                            <g class="graph-visual-edges">
                              <g v-for="edge in graphVisualEdges" :key="edge.id">
                                <line
                                  :x1="edge.from.x"
                                  :y1="edge.from.y"
                                  :x2="edge.to.x"
                                  :y2="edge.to.y"
                                  stroke="#c8d7ea"
                                  stroke-width="2"
                                  marker-end="url(#graph-arrow)"
                                />
                                <text
                                  :x="edge.labelX"
                                  :y="edge.labelY"
                                  class="graph-visual-edge-label"
                                >
                                  {{ edge.label }}
                                </text>
                              </g>
                            </g>
                            <g class="graph-visual-nodes">
                              <g
                                v-for="node in graphVisualNodes"
                                :key="node.id"
                                class="graph-visual-node"
                                :class="`graph-visual-node--${node.kind}`"
                              >
                                <circle :cx="node.x" :cy="node.y" :r="node.radius" />
                                <text :x="node.x" :y="node.y - 4" text-anchor="middle" class="graph-visual-node-title">
                                  {{ node.shortLabel }}
                                </text>
                                <text :x="node.x" :y="node.y + 13" text-anchor="middle" class="graph-visual-node-type">
                                  {{ node.typeLabel }}
                                </text>
                              </g>
                            </g>
                          </svg>
                        </div>
                        <div class="graph-list">
                          <article v-for="(node, index) in detail.graphContext" :key="`${detail.id}-graph-${index}`" class="graph-card">
                            <div class="graph-card-head">
                              <strong>{{ formatGraphNodeTitle(node, index) }}</strong>
                              <el-tag size="small" effect="plain">{{ formatGraphNodeType(node) }}</el-tag>
                            </div>
                            <small v-if="formatGraphNodeSource(node)">{{ formatGraphNodeSource(node) }}</small>
                            <p>{{ formatGraphNodeContent(node) }}</p>
                          </article>
                        </div>
                      </section>
                    </div>
                    <p v-else class="detail-empty">当前记录没有保留推理过程或知识图谱上下文。</p>
                  </div>
                </el-tab-pane>

                <el-tab-pane label="对话上下文" name="conversation">
                  <div class="detail-tab-stack">
                    <div v-if="conversationMessages.length" class="chat-conversation-shell conversation-replay-shell">
                      <div class="chat-conversation-main">
                        <div v-if="conversationContextNotice" class="conversation-context-notice">
                          {{ conversationContextNotice }}
                        </div>
                        <div class="message-list">
                          <div
                            v-for="(turn, index) in conversationTurns"
                            :key="turn.id || `fallback-turn-${index}`"
                            :class="[
                              'message-wrapper',
                              conversationMessageRole(turn),
                              {
                                'is-current': isCurrentConversationTurn(turn),
                                'is-current-prompt': isCurrentConversationPrompt(turn)
                              }
                            ]"
                          >
                            <div class="avatar">
                              <img
                                :src="conversationMessageRole(turn) === 'system' ? chatQueryAvatar : chatPeopleAvatar"
                                alt=""
                                aria-hidden="true"
                              />
                            </div>
                            <div class="msg-content">
                              <div
                                v-if="conversationTurnMarkerLabel(turn)"
                                class="conversation-current-marker"
                                :class="{ 'is-prompt': isCurrentConversationPrompt(turn) && !isCurrentConversationTurn(turn) }"
                              >
                                {{ conversationTurnMarkerLabel(turn) }}
                              </div>
                              <div class="bubble">
                                <span>{{ turn.messageText || '该轮消息未保留正文。' }}</span>
                              </div>
                              <details v-if="conversationThinkingLogs(turn).length" class="thinking-details">
                                <summary>查看思考过程（{{ conversationThinkingLogs(turn).length }}步）</summary>
                                <ol class="thinking-list">
                                  <li
                                    v-for="(line, lineIndex) in conversationThinkingLogs(turn)"
                                    :key="`${turn.id || index}-thinking-${lineIndex}`"
                                  >
                                    {{ line }}
                                  </li>
                                </ol>
                              </details>
                              <div v-if="turn.artifacts?.length" class="conversation-artifact-list">
                                <div
                                  v-for="artifact in turn.artifacts"
                                  :key="artifact.id || `${turn.id || index}-${artifact.historyId || artifact.artifactType}`"
                                  class="advanced-dialog-entry"
                                  :class="{ 'is-current': isCurrentConversationArtifact(artifact) }"
                                >
                                  <div class="advanced-dialog-entry__head">
                                    <div class="advanced-dialog-entry__main">
                                      <div class="advanced-dialog-entry__type">{{ artifactTypeDisplay(artifact) }}</div>
                                      <div class="advanced-dialog-entry__title">
                                        {{ conversationArtifactTitle(artifact) }}
                                      </div>
                                      <div v-if="conversationArtifactSubtitle(artifact)" class="advanced-dialog-entry__summary">
                                        {{ conversationArtifactSubtitle(artifact) }}
                                      </div>
                                      <div v-if="conversationAdvancedRuleInfo(artifact).has" class="advanced-dialog-entry__rule">
                                        <div>
                                          <span>命中规则</span>
                                          <strong>{{ conversationAdvancedRuleInfo(artifact).ruleName || conversationAdvancedRuleInfo(artifact).ruleCode }}</strong>
                                        </div>
                                        <div v-if="conversationAdvancedRuleInfo(artifact).ruleCode">
                                          <span>规则编码</span>
                                          <strong>{{ conversationAdvancedRuleInfo(artifact).ruleCode }}</strong>
                                        </div>
                                        <div v-if="conversationAdvancedRuleInfo(artifact).scenarioType">
                                          <span>推荐场景</span>
                                          <strong>{{ conversationAdvancedRuleInfo(artifact).scenarioLabel }}</strong>
                                        </div>
                                        <div v-if="conversationAdvancedRuleInfo(artifact).explain" class="advanced-dialog-entry__rule-explain">
                                          <span>推荐说明</span>
                                          <strong>{{ conversationAdvancedRuleInfo(artifact).explain }}</strong>
                                        </div>
                                      </div>
                                    </div>
                                    <div v-if="!isConversationSqlArtifact(artifact)" class="advanced-dialog-entry__actions">
                                      <template v-if="conversationAdvancedAnalysisPayload(artifact)">
                                        <el-tag
                                          size="small"
                                          effect="light"
                                          :type="conversationAdvancedAnalysisPayload(artifact).status === '模拟生成' ? 'warning' : 'success'"
                                        >
                                          {{ conversationAdvancedAnalysisPayload(artifact).status || '已生成' }}
                                        </el-tag>
                                        <el-button size="small" type="primary" plain @click="openConversationAdvancedAnalysisDialog(artifact)">
                                          查看详情
                                        </el-button>
                                      </template>
                                      <template v-else>
                                        <el-tag v-if="artifact.historyId" size="small" effect="plain">#{{ artifact.historyId }}</el-tag>
                                        <el-tag v-if="artifact.riskLevel" size="small" :type="riskTagType(artifact.riskLevel)" effect="light">{{ artifact.riskLevel }}</el-tag>
                                        <el-tag v-if="artifact.chartType" size="small" effect="plain">{{ chartTypeLabel(artifact.chartType) }}</el-tag>
                                        <el-tag v-if="isCurrentConversationArtifact(artifact)" size="small" type="primary" effect="light">当前记录</el-tag>
                                      </template>
                                    </div>
                                  </div>
                                  <div v-if="!isConversationSqlArtifact(artifact) && !conversationAdvancedAnalysisPayload(artifact) && conversationArtifactChartPayload(artifact)" class="conversation-chart-preview">
                                    <DashboardChart
                                      :payload="conversationArtifactChartPayload(artifact)"
                                      :display-title="artifact.summary || artifact.message || artifact.question || '图表结果'"
                                      hide-title
                                    />
                                  </div>
                                  <div
                                    v-if="artifact.sqlText && (isConversationSqlArtifact(artifact) || (!isConversationChartArtifact(artifact) && !conversationAdvancedAnalysisPayload(artifact) && !conversationArtifactChartPayload(artifact)))"
                                    class="sql-block"
                                  >
                                    <div class="sql-head">
                                      <div class="sql-title">生成的 SQL</div>
                                      <el-button size="small" text type="primary" @click="copySql(artifact.sqlText)">复制</el-button>
                                    </div>
                                    <pre class="sql-code">{{ artifact.sqlText }}</pre>
                                  </div>
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                    <p v-else class="detail-empty">当前记录没有可展示的对话上下文。</p>
                  </div>
                </el-tab-pane>
              </el-tabs>
            </main>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-dialog
      v-model="conversationAdvancedAnalysisVisible"
      :title="conversationAdvancedAnalysisActive ? advancedAnalysisTypeLabel(conversationAdvancedAnalysisActive.type) : '高级分析'"
      width="860px"
      destroy-on-close
      append-to-body
      class="advanced-analysis-dialog admin-advanced-analysis-dialog"
    >
      <AdvancedAnalysisCard
        v-if="conversationAdvancedAnalysisActive"
        :analysis="conversationAdvancedAnalysisActive"
        :show-save-action="false"
        :show-pin-action="false"
        :show-manage-alerts-action="false"
        @recalculate="notifyAdminAdvancedReplayOnly"
      />
    </el-dialog>

    <el-dialog
      v-model="fullGraphVisible"
      title="完整图谱关系"
      width="min(1120px, 92vw)"
      class="graph-full-dialog"
      append-to-body
    >
      <div v-if="fullGraphVisual.nodes.length" class="graph-full-layout">
        <section class="graph-full-panel graph-full-panel--canvas">
          <div class="graph-full-head">
            <strong>{{ fullGraphVisual.nodes.length }} 个节点 · {{ fullGraphVisual.edges.length }} 条关系</strong>
            <span>展示当前历史记录命中的知识图谱节点与关系</span>
          </div>
          <svg class="graph-full-canvas" viewBox="0 0 1120 620" role="img" aria-label="完整知识图谱关系图">
            <defs>
              <marker id="graph-full-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
                <path d="M0,0 L9,4.5 L0,9 Z" fill="#7c8da5" />
              </marker>
            </defs>
            <g class="graph-visual-edges">
              <g v-for="edge in fullGraphVisual.edges" :key="edge.id">
                <line
                  :x1="edge.from.x"
                  :y1="edge.from.y"
                  :x2="edge.to.x"
                  :y2="edge.to.y"
                  stroke="#b7c7dc"
                  stroke-width="2"
                  marker-end="url(#graph-full-arrow)"
                />
                <text :x="edge.labelX" :y="edge.labelY" class="graph-visual-edge-label">
                  {{ edge.label }}
                </text>
              </g>
            </g>
            <g class="graph-visual-nodes">
              <g
                v-for="node in fullGraphVisual.nodes"
                :key="node.id"
                class="graph-visual-node"
                :class="`graph-visual-node--${node.kind}`"
              >
                <circle :cx="node.x" :cy="node.y" :r="node.radius" />
                <text :x="node.x" :y="node.y - 4" text-anchor="middle" class="graph-visual-node-title">
                  {{ node.shortLabel }}
                </text>
                <text :x="node.x" :y="node.y + 13" text-anchor="middle" class="graph-visual-node-type">
                  {{ node.typeLabel }}
                </text>
              </g>
            </g>
          </svg>
        </section>
        <section class="graph-full-panel graph-full-panel--list">
          <div class="graph-full-head">
            <strong>关系明细</strong>
            <span>按图谱关系边展开</span>
          </div>
          <div v-if="fullGraphRelations.length" class="graph-relation-list">
            <article v-for="item in fullGraphRelations" :key="item.id" class="graph-relation-item">
              <strong>{{ item.fromLabel }}</strong>
              <span>{{ item.label }}</span>
              <strong>{{ item.toLabel }}</strong>
            </article>
          </div>
          <p v-else class="detail-empty">当前图谱节点之间没有可展示的关系边。</p>
        </section>
      </div>
      <p v-else class="detail-empty">当前记录没有可展示的完整图谱关系。</p>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, RefreshRight, View } from '@element-plus/icons-vue'
import { API_BASE, http } from '../../api/http'
import AdvancedAnalysisCard from '../../components/AdvancedAnalysisCard.vue'
import DashboardChart from '../../components/dashboard/DashboardChart.vue'
import LegacyInlineChart from '../../components/dashboard/LegacyInlineChart.vue'
import { formatChartRecommendationExplain } from '../../utils/chartRecommendationText'
import chatPeopleAvatar from '../../assets/chat-people.png'
import chatQueryAvatar from '../../assets/chat-query-avatar.png'
import {
  deleteAdminChatHistoryBatch,
  fetchAdminChatHistory,
  fetchAdminChatHistoryAnalytics,
  fetchAdminChatHistoryContext,
  fetchAdminChatHistoryDetail,
  rerunAdminChatHistory
} from '../../api/adminChatHistory'

const loading = ref(false)
const exporting = ref(false)
const rerunning = ref(false)
const detailVisible = ref(false)
const fullGraphVisible = ref(false)
const conversationAdvancedAnalysisVisible = ref(false)
const conversationAdvancedAnalysisActive = ref(null)
const detail = ref(null)
const detailActiveTab = ref('query')
const rows = ref([])
const selectedIds = ref([])
const summary = ref({})
const governance = ref({})
const analytics = ref({
  trends: {},
  performance: {},
  routeAudit: {}
})
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const dateRange = ref([])
const filters = reactive({
  keyword: '',
  userId: '',
  tableName: '',
  sourceType: '',
  chartType: '',
  riskLevel: '',
  executionStatus: '',
  modelType: '',
  cacheHit: null,
  slowQuery: null,
  sortDirection: 'DESC'
})

const previewColumns = computed(() => {
  const first = detail.value?.snapshotPreviewRows?.[0]
  return first ? Object.keys(first) : []
})

const getDetailChartSnapshot = (entry = detail.value) => {
  const snapshot = entry?.chartSnapshot
  if (snapshot && typeof snapshot === 'object') return snapshot
  if (typeof snapshot === 'string') {
    try {
      const parsed = JSON.parse(snapshot)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

const normalizeChartSnapshot = (snapshot) => {
  if (snapshot && typeof snapshot === 'object') return snapshot
  if (typeof snapshot === 'string') {
    try {
      const parsed = JSON.parse(snapshot)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

const conversationArtifactChartPayload = (artifact) => {
  if (!artifact || typeof artifact !== 'object') return null
  const nestedArtifact = artifact.artifact && typeof artifact.artifact === 'object' ? artifact.artifact : {}
  let snapshot = normalizeChartSnapshot(
    artifact.chartSnapshot ||
    nestedArtifact.chartSnapshot ||
    (Array.isArray(nestedArtifact.data) ? nestedArtifact : null) ||
    (Array.isArray(artifact.data) ? artifact : null)
  )
  if ((!Array.isArray(snapshot.data) || !snapshot.data.length) && String(artifact.historyId || '') === String(detail.value?.id || '')) {
    const detailSnapshot = getDetailChartSnapshot(detail.value)
    if (Array.isArray(detailSnapshot.data) && detailSnapshot.data.length) {
      snapshot = detailSnapshot
    }
  }
  if (!Array.isArray(snapshot.data) || !snapshot.data.length) return null
  return {
    id: artifact.historyId || artifact.id,
    queryText: artifact.question || artifact.summary || artifact.message || '',
    chartType: artifact.chartType || snapshot.chartType || nestedArtifact.chartType,
    chartSnapshot: {
      ...snapshot,
      chartType: snapshot.chartType || artifact.chartType || nestedArtifact.chartType,
      message: snapshot.message || artifact.summary || artifact.message || artifact.question
    }
  }
}

const isConversationSqlArtifact = (artifact) => {
  const type = String(artifact?.artifactType || artifact?.artifactTypeLabel || '').trim().toUpperCase()
  return type === 'SQL'
}

const isConversationChartArtifact = (artifact) => {
  if (isConversationSqlArtifact(artifact)) return false
  const type = String(artifact?.artifactType || artifact?.artifactTypeLabel || '').trim().toUpperCase()
  return type === 'CHART' || artifact?.hasChart === true
}

const asObject = (value) => (value && typeof value === 'object' && !Array.isArray(value) ? value : {})

const firstText = (...values) => values
  .map(value => String(value ?? '').trim())
  .find(Boolean) || ''

const scalarText = (value) => {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value) || typeof value === 'object') return ''
  const text = String(value).trim()
  return text && !['null', 'undefined', 'nan'].includes(text.toLowerCase()) ? text : ''
}

const firstScalar = (sources, keys) => {
  for (const source of sources) {
    for (const key of keys) {
      const text = scalarText(source?.[key])
      if (text) return text
    }
  }
  return ''
}

const firstRaw = (sources, keys) => {
  for (const source of sources) {
    for (const key of keys) {
      const value = source?.[key]
      if (value !== null && value !== undefined && value !== '') return value
    }
  }
  return undefined
}

const collectAlertSources = (...values) => {
  const queue = values.flat().filter(Boolean)
  const sources = []
  const seen = new Set()
  const nestedKeys = [
    'params',
    'fieldMapping',
    'alertMeta',
    'alertRule',
    'alertRuleCreated',
    'alertRuleDraft',
    'rule',
    'event',
    'alertEvent',
    'triggerResult',
    'advancedAnalysis',
    'chartSnapshot',
    'snapshot'
  ]
  while (queue.length && sources.length < 80) {
    const value = queue.shift()
    if (!value || typeof value !== 'object' || Array.isArray(value) || seen.has(value)) continue
    seen.add(value)
    sources.push(value)
    nestedKeys.forEach((key) => {
      const nested = value[key]
      if (nested && typeof nested === 'object' && !Array.isArray(nested)) queue.push(nested)
    })
  }
  return sources
}

const normalizeAdvancedAnalysisType = (value) => {
  const normalized = String(value || '').trim().replace(/[-_\s]/g, '').toLowerCase()
  if (normalized.includes('forecast')) return 'forecast'
  if (normalized.includes('whatif')) return 'whatIf'
  if (normalized.includes('alert') || normalized.includes('warning') || normalized.includes('prewarning')) return 'alert'
  return ''
}

const firstAdvancedAnalysisType = (...values) => values
  .map(value => normalizeAdvancedAnalysisType(value))
  .find(Boolean) || ''

const advancedAnalysisTypeLabel = (type) => {
  if (type === 'forecast') return '时序预测'
  if (type === 'whatIf') return 'What-if 推演'
  if (type === 'alert') return '智能预警'
  return '高级分析'
}

const advancedScenarioLabel = (scenarioType) => {
  const value = String(scenarioType || '').trim().toUpperCase()
  if (value === 'TIME_SERIES') return '时序趋势'
  if (value === 'GROUP_COMPARE') return '分组对比'
  if (value === 'RATIO') return '占比分析'
  if (value === 'DETAIL') return '明细数据'
  if (value === 'SCENARIO_SIMULATION') return '情景推演'
  if (value === 'ADVANCED_ALERT') return '智能预警'
  if (value === 'CUSTOM') return '自定义规则'
  return value || '自动推荐'
}

const formatAdvancedNumber = (value, fallback = '未记录') => {
  const number = Number(value)
  if (!Number.isFinite(number)) return scalarText(value) || fallback
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(1)}万`
  return Number.isInteger(number) ? String(number) : number.toFixed(2)
}

const alertOperatorLabel = (operator) => {
  const value = String(operator || '').trim().toLowerCase()
  if (value === 'gt') return '高于阈值'
  if (value === 'zscore') return 'Z-Score 异常波动'
  if (value === 'lt') return '低于阈值'
  return value || '未记录判断条件'
}

const alertCycleLabel = (cycle) => {
  const value = String(cycle || '').trim()
  const normalized = value.toLowerCase()
  const labels = {
    hourly: '每小时检测',
    hour: '每小时检测',
    daily: '每日检测',
    day: '每日检测',
    weekly: '每周检测',
    week: '每周检测',
    monthly: '每月检测',
    month: '每月检测'
  }
  return labels[normalized] || value || '未记录检测周期'
}

const parseAlertChannelValue = (value) => {
  if (Array.isArray(value)) return value
  if (value === null || value === undefined || value === '') return []
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return []
    if (text.startsWith('[')) {
      try {
        const parsed = JSON.parse(text)
        if (Array.isArray(parsed)) return parsed
      } catch {
        // fall through to delimiter parsing
      }
    }
    return text.split(/[,+，、/]/).map(item => item.trim()).filter(Boolean)
  }
  return [value]
}

const formatAlertChannel = (channels) => {
  const values = parseAlertChannelValue(channels)
  const labels = values.map((item) => {
    const value = String(item || '').trim()
    const normalized = value.toLowerCase()
    if (normalized === 'email' || value === '邮件') return '邮件'
    if (normalized === 'dingtalk' || value === '钉钉') return '钉钉'
    if (normalized === 'both' || value === '邮件 + 钉钉') return '邮件 + 钉钉'
    if (normalized === 'webhook') return 'Webhook'
    return value
  }).filter(Boolean)
  const unique = [...new Set(labels)]
  if (unique.includes('邮件 + 钉钉')) return '邮件 + 钉钉'
  return unique.length ? unique.join(' + ') : '未记录通知渠道'
}

const formatAlertStatusLabel = (status) => {
  const text = String(status || '').trim()
  const value = text.toUpperCase()
  const labels = {
    ACTIVE: '已启用',
    ENABLED: '已启用',
    SAVED: '已保存',
    CREATED: '已创建',
    DRAFT: '待确认',
    PENDING: '待处理',
    OPEN: '待处理',
    ACK: '已确认',
    ACKED: '已确认',
    CLOSED: '已关闭',
    DISABLED: '已停用',
    SUCCESS: '推送成功',
    FAILED: '推送失败',
    WARNING: '已触发',
    TRIGGERED: '已触发'
  }
  return labels[value] || text || '未记录状态'
}

const alertStatusTagType = (status) => {
  const raw = String(status || '').trim()
  const value = raw.toUpperCase()
  if (['推送失败', '已停用', '已关闭'].includes(raw)) return 'danger'
  if (['待确认', '待处理', '已触发'].includes(raw)) return 'warning'
  if (['已启用', '已保存', '已创建', '推送成功', '已确认'].includes(raw)) return 'success'
  if (['FAILED', 'DISABLED', 'CLOSED'].includes(value)) return 'danger'
  if (['DRAFT', 'PENDING', 'OPEN', 'WARNING', 'TRIGGERED'].includes(value)) return 'warning'
  if (['ACTIVE', 'ENABLED', 'SAVED', 'CREATED', 'SUCCESS', 'ACK', 'ACKED'].includes(value)) return 'success'
  return 'info'
}

const buildAlertTriggerResult = (info) => {
  if (info.triggerResult) return info.triggerResult
  if (info.actualValue || info.baselineValue || info.zScore) {
    const parts = []
    if (info.bucketName) parts.push(`检测窗口：${info.bucketName}`)
    if (info.actualValue) parts.push(`实际值：${info.actualValue}`)
    if (info.thresholdText && info.thresholdText !== '未记录阈值') parts.push(`阈值：${info.thresholdText}`)
    if (info.baselineValue) parts.push(`历史基线：${info.baselineValue}`)
    if (info.zScore) parts.push(`Z-Score：${info.zScore}`)
    return parts.join('，')
  }
  const status = String(info.status || '').trim().toUpperCase()
  if (['ACTIVE', 'ENABLED', 'SAVED', 'CREATED'].includes(status)) return '规则已创建，等待离线检测触发。'
  if (['DRAFT', 'PENDING'].includes(status)) return '规则待确认，尚未进入离线检测。'
  return '未记录触发结果'
}

const buildAlertInfo = (...sourceValues) => {
  const sources = collectAlertSources(...sourceValues)
  if (!sources.length) return null
  const operator = firstScalar(sources, ['operator', 'compareOperator', 'condition'])
  const thresholdRaw = firstRaw(sources, ['threshold', 'thresholdValue', 'threshold_value'])
  const rawChannels = firstRaw(sources, ['channels', 'notificationChannels', 'notifyChannels', 'channel', 'notifyChannel', 'pushChannel'])
  const actualValueRaw = firstRaw(sources, ['actualValue', 'actual', 'value', 'currentValue'])
  const baselineRaw = firstRaw(sources, ['baselineValue', 'baseline'])
  const zScoreRaw = firstRaw(sources, ['zScore', 'z_score'])
  const status = firstScalar(sources, ['status', 'ruleStatus', 'eventStatus', 'pushStatus']) || 'SAVED'
  const info = {
    title: firstScalar(sources, ['title', 'ruleName', 'name']) || '智能预警规则',
    ruleName: firstScalar(sources, ['ruleName', 'name', 'title']),
    ruleCode: firstScalar(sources, ['ruleCode', 'code']),
    ruleId: firstScalar(sources, ['ruleId']),
    eventId: firstScalar(sources, ['eventId']),
    metric: firstScalar(sources, ['metric', 'metricField', 'targetMetric', 'metricKey']),
    metricField: firstScalar(sources, ['metricField', 'targetMetric', 'metricKey']),
    timeField: firstScalar(sources, ['timeField', 'timeKey']),
    tableName: firstScalar(sources, ['tableName', 'queryTableName']),
    operator,
    threshold: thresholdRaw,
    thresholdText: operator === 'zscore' && (thresholdRaw === null || thresholdRaw === undefined || thresholdRaw === '')
      ? 'Z-Score >= 3'
      : formatAdvancedNumber(thresholdRaw, '未记录阈值'),
    detectionCycle: firstScalar(sources, ['detectionCycle', 'cycle', 'schedule']),
    channelLabel: formatAlertChannel(rawChannels),
    channels: parseAlertChannelValue(rawChannels),
    status,
    statusLabel: formatAlertStatusLabel(status),
    triggerResult: firstScalar(sources, ['triggerResult', 'alertResult', 'reason', 'resultReason', 'message']),
    bucketName: firstScalar(sources, ['bucketName', 'period', 'window']),
    actualValue: formatAdvancedNumber(actualValueRaw, ''),
    baselineValue: formatAdvancedNumber(baselineRaw, ''),
    zScore: formatAdvancedNumber(zScoreRaw, '')
  }
  info.conditionText = info.operator
    ? `${alertOperatorLabel(info.operator)} ${info.thresholdText}`
    : info.thresholdText
  info.detectionCycleLabel = alertCycleLabel(info.detectionCycle)
  info.triggerResult = buildAlertTriggerResult(info)
  return info
}

const alertInfoItems = (info) => {
  if (!info) return []
  return [
    { label: '规则名称', value: info.ruleName || info.title || info.ruleCode || '未记录规则名称' },
    { label: '指标', value: info.metric || info.metricField || '未记录指标' },
    { label: '阈值', value: info.conditionText || info.thresholdText || '未记录阈值' },
    { label: '检测周期', value: info.detectionCycleLabel || '未记录检测周期' },
    { label: '通知渠道', value: info.channelLabel || '未记录通知渠道' },
    { label: '状态', value: info.statusLabel || '未记录状态' },
    { label: '触发结果', value: info.triggerResult || '未记录触发结果', wide: true }
  ]
}

const conversationAdvancedAnalysisPayload = (artifact) => {
  if (!artifact || typeof artifact !== 'object') return null
  const nested = asObject(artifact.artifact)
  const embedded = asObject(nested.advancedAnalysis || artifact.advancedAnalysis)
  const snapshot = normalizeChartSnapshot(
    artifact.chartSnapshot ||
    nested.chartSnapshot ||
    embedded.chartSnapshot ||
    (Array.isArray(nested.data) ? nested : null) ||
    (Array.isArray(embedded.data) ? embedded : null)
  )
  const type = firstAdvancedAnalysisType(
    artifact.artifactType,
    artifact.intentType,
    artifact.type,
    nested.type,
    embedded.type,
    snapshot.type,
    snapshot.advancedAnalysisType,
    snapshot.fieldMapping?.mappingType
  )
  const fieldMapping = {
    ...asObject(snapshot.fieldMapping),
    ...asObject(nested.fieldMapping),
    ...asObject(embedded.fieldMapping)
  }
  const forecastMeta = {
    ...asObject(snapshot.forecastMeta),
    ...asObject(nested.forecastMeta),
    ...asObject(embedded.forecastMeta)
  }
  const params = {
    ...asObject(snapshot.params),
    ...asObject(artifact.params),
    ...asObject(embedded.params),
    ...asObject(nested.params)
  }
  const alertInfo = buildAlertInfo(
    artifact,
    nested,
    embedded,
    snapshot,
    params,
    snapshot.alertMeta,
    snapshot.alertRule,
    snapshot.alertRuleCreated,
    snapshot.alertRuleDraft,
    nested.alertRuleCreated,
    nested.alertRuleDraft,
    embedded.alertRuleCreated,
    embedded.alertRuleDraft
  )
  const hasAlertSignal = Boolean(alertInfo && (
    alertInfo.operator ||
    alertInfo.threshold !== undefined ||
    alertInfo.actualValue ||
    alertInfo.zScore ||
    alertInfo.eventId ||
    alertInfo.ruleId ||
    Object.keys(asObject(snapshot.alertMeta)).length ||
    Object.keys(asObject(snapshot.alertRule)).length ||
    Object.keys(asObject(snapshot.alertRuleCreated)).length ||
    Object.keys(asObject(snapshot.alertRuleDraft)).length ||
    Object.keys(asObject(nested.alertRuleCreated)).length ||
    Object.keys(asObject(nested.alertRuleDraft)).length ||
    Object.keys(asObject(embedded.alertRuleCreated)).length ||
    Object.keys(asObject(embedded.alertRuleDraft)).length
  ))
  const resolvedType = type || (hasAlertSignal ? 'alert' : '')
  if (!resolvedType) return null
  const series = Array.isArray(embedded.series) && embedded.series.length
    ? embedded.series
    : Array.isArray(nested.series) && nested.series.length
      ? nested.series
      : Array.isArray(snapshot.data) ? snapshot.data : []
  const forecastRows = series.filter(item => item && typeof item === 'object' && item.forecast != null)
  if (!params.algorithm && forecastMeta.algorithm) params.algorithm = forecastMeta.algorithm
  if (!params.confidence && forecastMeta.confidence) params.confidence = forecastMeta.confidence
  if (!params.horizon && forecastRows.length) params.horizon = forecastRows.length
  if (!params.metricField) params.metricField = firstText(
    embedded.metricField,
    nested.metricField,
    forecastMeta.metricField,
    alertInfo?.metricField,
    fieldMapping.metric,
    fieldMapping.metricKey
  )
  if (resolvedType === 'alert' && alertInfo) {
    if (!params.operator && alertInfo.operator) params.operator = alertInfo.operator
    if ((params.threshold === undefined || params.threshold === null || params.threshold === '') && alertInfo.threshold !== undefined) {
      params.threshold = alertInfo.threshold
    }
    if (!params.detectionCycle && alertInfo.detectionCycle) params.detectionCycle = alertInfo.detectionCycle
    if (!params.channel && alertInfo.channelLabel) params.channel = alertInfo.channelLabel
    if ((!Array.isArray(params.channels) || !params.channels.length) && alertInfo.channels.length) {
      params.channels = alertInfo.channels
    }
    if (!params.metricField && alertInfo.metricField) params.metricField = alertInfo.metricField
    if (!params.timeField && alertInfo.timeField) params.timeField = alertInfo.timeField
  }
  const fallbackTitle = resolvedType === 'alert'
    ? `${alertInfo?.metric || alertInfo?.metricField || firstText(fieldMapping.metric, fieldMapping.metricKey, params.metricField, '指标')}预警规则`
    : `${firstText(fieldMapping.metric, fieldMapping.metricKey, params.metricField, '指标')}趋势预测`
  const fallbackSummary = resolvedType === 'forecast'
    ? '已基于真实历史数据生成预测结果，预测值与置信区间由后端算法计算。'
    : resolvedType === 'alert'
      ? '智能预警规则或预警事件已生成，可回放规则配置、阈值条件与触发结果。'
      : '高级分析结果已生成。'
  const chartRecommendation = asObject(embedded.chartRecommendation || nested.chartRecommendation || snapshot.chartRecommendation)
  const alertRuleRecommendation = alertInfo
    ? {
      ruleCode: alertInfo.ruleCode || alertInfo.ruleId,
      ruleName: alertInfo.ruleName || alertInfo.title,
      scenarioType: 'ADVANCED_ALERT',
      status: alertInfo.status,
      explain: alertInfo.triggerResult
    }
    : {}
  return {
    ...embedded,
    id: embedded.id || artifact.id || artifact.historyId,
    type: resolvedType,
    title: firstText(embedded.title, nested.title, alertInfo?.title, artifact.message, artifact.summary, fallbackTitle),
    summary: firstText(embedded.summary, nested.summary, artifact.summary, artifact.message, fallbackSummary),
    status: firstText(embedded.status, nested.status, artifact.status, alertInfo?.statusLabel, '已生成'),
    tableName: firstText(embedded.tableName, nested.tableName, artifact.tableName, snapshot.tableName, '当前对话上下文'),
    metric: firstText(embedded.metric, alertInfo?.metric, fieldMapping.metric, fieldMapping.metricKey, params.metricField, forecastMeta.metricField, '自动推断'),
    metricField: firstText(embedded.metricField, alertInfo?.metricField, params.metricField, forecastMeta.metricField, fieldMapping.metricKey),
    timeRange: firstText(embedded.timeRange, nested.timeRange, resolvedType === 'alert' ? alertInfo?.detectionCycleLabel : '', forecastRows.length ? `未来 ${forecastRows.length} 期` : ''),
    params,
    fieldMapping,
    forecastMeta,
    series,
    data: Array.isArray(embedded.data) && embedded.data.length ? embedded.data : series,
    insights: Array.isArray(embedded.insights) ? embedded.insights : Array.isArray(nested.insights) ? nested.insights : [],
    explanation: asObject(embedded.explanation || nested.explanation),
    optionTemplate: asObject(embedded.optionTemplate || nested.optionTemplate || snapshot.optionTemplate),
    chartRecommendation: Object.keys(chartRecommendation).length ? chartRecommendation : alertRuleRecommendation,
    ruleRecommendation: asObject(embedded.ruleRecommendation || nested.ruleRecommendation || alertRuleRecommendation),
    alertRuleCreated: asObject(embedded.alertRuleCreated || nested.alertRuleCreated || snapshot.alertRuleCreated),
    alertRuleDraft: asObject(embedded.alertRuleDraft || nested.alertRuleDraft || snapshot.alertRuleDraft),
    alertMeta: asObject(embedded.alertMeta || nested.alertMeta || snapshot.alertMeta),
    alertInfo
  }
}

const conversationArtifactTitle = (artifact) => {
  const analysis = conversationAdvancedAnalysisPayload(artifact)
  return analysis?.title || artifact.summary || artifact.message || artifact.question || '未记录产物摘要'
}

const conversationArtifactSubtitle = (artifact) => {
  const analysis = conversationAdvancedAnalysisPayload(artifact)
  return analysis?.summary || artifact.tableName || ''
}

const conversationAlertInfo = (artifact) => {
  const analysis = conversationAdvancedAnalysisPayload(artifact)
  if (analysis?.type !== 'alert') return null
  const info = analysis.alertInfo || buildAlertInfo(
    analysis,
    analysis.params,
    analysis.alertMeta,
    analysis.alertRuleCreated,
    analysis.alertRuleDraft,
    analysis.chartRecommendation,
    analysis.ruleRecommendation
  )
  if (!info) return null
  return {
    ...info,
    title: info.title || analysis.title || '智能预警规则',
    statusLabel: info.statusLabel || formatAlertStatusLabel(info.status)
  }
}

const conversationAdvancedRuleInfo = (artifact) => {
  const analysis = conversationAdvancedAnalysisPayload(artifact)
  const alertInfo = analysis?.type === 'alert' ? conversationAlertInfo(artifact) : null
  const recommendation = asObject(analysis?.chartRecommendation || analysis?.ruleRecommendation)
  const optionTemplate = asObject(analysis?.optionTemplate)
  const raw = asObject(
    recommendation.ruleCode || recommendation.ruleName || recommendation.scenarioType
      ? recommendation
      : optionTemplate.recommendation || optionTemplate.rule || {}
  )
  const ruleCode = firstText(raw.ruleCode, raw.code)
  const ruleName = firstText(raw.ruleName, raw.name)
  const scenarioType = firstText(raw.scenarioType, raw.scenario, alertInfo ? 'ADVANCED_ALERT' : '')
  const rawExplain = firstText(raw.explain, raw.description, raw.reason)
  const explain = formatChartRecommendationExplain(rawExplain, { ruleCode, ruleName, scenarioType })
  return {
    has: Boolean(ruleCode || ruleName || scenarioType || explain || alertInfo?.ruleCode || alertInfo?.ruleName),
    ruleCode: ruleCode || alertInfo?.ruleCode || alertInfo?.ruleId || '',
    ruleName: ruleName || alertInfo?.ruleName || alertInfo?.title || '',
    scenarioType,
    scenarioLabel: advancedScenarioLabel(scenarioType),
    explain: explain || alertInfo?.triggerResult || ''
  }
}

const openConversationAdvancedAnalysisDialog = (artifact) => {
  const analysis = conversationAdvancedAnalysisPayload(artifact)
  if (!analysis) return
  conversationAdvancedAnalysisActive.value = analysis
  conversationAdvancedAnalysisVisible.value = true
}

const notifyAdminAdvancedReplayOnly = () => {
  ElMessage.info('管理员对话上下文仅用于回放查看，请在用户端高级分析中重新计算或保存方案。')
}

const isForecastDetailSnapshot = (entry = detail.value) => {
  const snapshot = getDetailChartSnapshot(entry)
  const type = String(snapshot.advancedAnalysisType || snapshot.fieldMapping?.mappingType || '').trim()
  if (type === 'forecast' || snapshot.forecastMeta) return true
  const rows = Array.isArray(snapshot.data) ? snapshot.data : []
  return rows.some(row =>
    row && typeof row === 'object' && (
      Object.prototype.hasOwnProperty.call(row, 'history') ||
      Object.prototype.hasOwnProperty.call(row, 'forecast') ||
      Object.prototype.hasOwnProperty.call(row, 'upper') ||
      Object.prototype.hasOwnProperty.call(row, 'lower') ||
      Object.prototype.hasOwnProperty.call(row, 'phase')
    )
  )
}

const normalizeModelCategoryText = (value) => {
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  const lower = text.toLowerCase()
  if (!text || upper === 'UNKNOWN' || upper === 'UNKONWN') return ''
  if (['预测算法', '情景推演', '智能预警'].includes(text)) return ''
  if (text === 'AI 解析成功') return '大模型解析成功'
  if (text === 'What-if' || text === 'WHAT_IF') return '情景推演'
  if (lower === 'python-ai-service') return '大模型解析服务'
  if (lower === 'redis-semantic-cache') return '语义缓存复用'
  if (lower === 'java-fallback') return '规则兜底解析'
  if (lower === 'java-federal-join') return '联邦关联直连'
  if (upper === 'AI_SUCCESS') return '大模型解析成功'
  if (upper === 'CACHE_REUSED') return '命中语义缓存'
  if (upper === 'RULE_FALLBACK') return '规则兜底'
  if (upper === 'FEDERAL_JOIN') return '联邦关联直连'
  if (upper === 'PARSE_FAILED') return '解析失败'
  if (upper === 'PARSED') return '已完成解析'
  return text
}

const advancedAnalysisKind = (entry = detail.value) => {
  const snapshot = getDetailChartSnapshot(entry)
  const resolvedType = firstAdvancedAnalysisType(
    entry?.artifactType ||
    '',
    entry?.intentType ||
    '',
    snapshot.advancedAnalysisType ||
    '',
    snapshot.type ||
    '',
    snapshot.fieldMapping?.mappingType ||
    ''
  )
  if (resolvedType) return resolvedType
  if (snapshot.forecastMeta || isForecastDetailSnapshot(entry)) return 'forecast'
  if (snapshot.whatIfMeta || snapshot.scenarioMeta || Array.isArray(snapshot.scenarios) || Array.isArray(snapshot.variables)) return 'whatIf'
  if (snapshot.alertMeta || snapshot.alertRule || snapshot.ruleId) return 'alert'
  if (snapshot.operator && (snapshot.threshold !== undefined || snapshot.actualValue !== undefined || snapshot.zScore !== undefined)) return 'alert'
  return ''
}

const advancedAnalysisModelLabel = (entry = detail.value) => {
  const kind = advancedAnalysisKind(entry)
  if (kind === 'forecast') return '预测算法'
  if (kind === 'whatIf') return '情景推演'
  if (kind === 'alert') return '智能预警'
  return ''
}

const displayModelCategory = (entry) => {
  const modelText = normalizeModelCategoryText(entry?.modelCategory) ||
    normalizeModelCategoryText(entry?.llmModelUsed) ||
    ''
  if (modelText) return modelText
  return advancedAnalysisKind(entry) ? 'LLM' : '未识别'
}

const detailIsAdvancedAnalysis = computed(() => Boolean(advancedAnalysisKind(detail.value)))
const shouldShowDetailSql = computed(() => !detailIsAdvancedAnalysis.value)
const detailModelTypeText = computed(() => displayModelCategory(detail.value))
const detailTurnNoText = computed(() => {
  const value = Number(detail.value?.turnNo)
  return Number.isFinite(value) && value > 0 ? `第 ${value} 轮` : '轮次未记录'
})
const detailConversationIdText = computed(() => {
  const value = String(detail.value?.conversationId || '').trim()
  return value ? `会话 ${value}` : '会话未关联'
})

const detailChartPayload = computed(() => {
  const current = detail.value
  const snapshot = getDetailChartSnapshot(current)
  if (!current || !snapshot || typeof snapshot !== 'object') return null
  if (advancedAnalysisKind(current) === 'alert') return null
  if (!Array.isArray(snapshot.data) || !snapshot.data.length) return null
  return {
    chartType: current.chartType || snapshot.chartType,
    chartSnapshot: snapshot
  }
})

const detailHasResultPreview = computed(() => (
  advancedAnalysisKind(detail.value) !== 'alert' &&
  Boolean(detailChartPayload.value || detail.value?.snapshotPreviewRows?.length)
))

const detailFieldMappingSummary = computed(() => summarizeFieldMapping(
  detail.value?.snapshotMetrics?.fieldMapping || getDetailChartSnapshot(detail.value)?.fieldMapping
))

const detailAlertInfo = computed(() => {
  const current = detail.value
  if (!current || advancedAnalysisKind(current) !== 'alert') return null
  const snapshot = getDetailChartSnapshot(current)
  const context = asObject(current.context)
  const turns = Array.isArray(current.conversationContext?.turns) ? current.conversationContext.turns : []
  const currentTurn = turns.find(turn => turn?.isCurrent) || {}
  const currentArtifact = turns
    .flatMap(turn => Array.isArray(turn.artifacts) ? turn.artifacts : [])
    .find(artifact => String(artifact?.historyId || '') === String(current.id || '')) || {}
  const currentArtifactPayload = conversationAdvancedAnalysisPayload(currentArtifact) || {}
  const info = buildAlertInfo(
    current,
    context,
    snapshot,
    snapshot.params,
    snapshot.alertMeta,
    snapshot.alertRule,
    snapshot.alertRuleCreated,
    snapshot.alertRuleDraft,
    currentTurn.context,
    currentArtifact,
    currentArtifact.artifact,
    currentArtifactPayload,
    currentArtifactPayload.alertInfo
  )
  if (!info) return null
  return {
    ...info,
    title: info.title || current.question || '智能预警规则',
    statusLabel: info.statusLabel || formatAlertStatusLabel(info.status)
  }
})

const detailAlertInfoItems = computed(() => alertInfoItems(detailAlertInfo.value))

const detailForecastMeta = computed(() => {
  const snapshot = getDetailChartSnapshot(detail.value)
  const meta = snapshot.forecastMeta
  return meta && typeof meta === 'object' ? meta : {}
})

const detailParseEngineText = computed(() => {
  const kind = advancedAnalysisKind(detail.value)
  if (kind === 'forecast') return detailForecastMeta.value.algorithm || '时序预测算法'
  if (kind === 'whatIf') return '拟合推演算法'
  if (kind === 'alert') return detailAlertInfo.value?.operator === 'zscore' ? 'Z-Score 异常检测' : '阈值预警检测'
  return normalizeModelCategoryText(detail.value?.engine) ||
    normalizeModelCategoryText(detail.value?.modelCategory) ||
    '未记录解析引擎'
})

const intentTypeLabel = (value) => {
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  if (!text) return '数据分析'
  const labelMap = {
    QUERY: '对话查询',
    QUERY_SQL: '数据查询',
    FOLLOWUP: '追问分析',
    COMPARE: '对比分析',
    DRILLDOWN: '下钻分析',
    EXPLAIN: '结果解释',
    FORECAST: '时序预测',
    ADVANCED_FORECAST: '时序预测',
    ADVANCED_WHAT_IF: '情景推演',
    ADVANCED_ALERT: '智能预警',
    ADVANCED_ANALYSIS: '高级分析',
    REPORT_GENERATE: '报告生成',
    AUDIT_QUERY: '审计查询',
    CLARIFY: '需求澄清'
  }
  return labelMap[upper] || text
}

const aiParseResultText = (status, fallback = '') => {
  const fromStatus = normalizeModelCategoryText(status)
  if (fromStatus) return fromStatus
  return normalizeModelCategoryText(fallback) || fallback || '解析信息缺失'
}

const detailQueryContentSubtitle = computed(() => (
  shouldShowDetailSql.value ? '原始问句与生成 SQL' : '原始问句与算法结果'
))

const detailRealSql = computed(() => String(detail.value?.generatedSql || detail.value?.sql || '').trim())

const detailSqlDisplayText = computed(() => {
  if (detailRealSql.value) return detailRealSql.value
  if (isForecastDetailSnapshot(detail.value)) {
    return '预测记录由时序算法基于历史序列生成，无需生成 SQL。可通过下方结果预览查看历史值、预测值和置信区间。'
  }
  return '无 SQL 记录'
})

const detailQuerySummaryText = computed(() => {
  const current = detail.value || {}
  if (advancedAnalysisKind(current) === 'alert') {
    const info = detailAlertInfo.value
    const parts = ['本次记录为智能预警产物']
    if (info?.ruleName) parts.push(`规则：${info.ruleName}`)
    if (info?.metric) parts.push(`指标：${info.metric}`)
    return `${parts.join('，')}。管理员可在摘要卡片中查看阈值、周期、通知渠道与触发结果。`
  }
  if (current.summaryText) return current.summaryText
  if (isForecastDetailSnapshot(current)) {
    const meta = detailForecastMeta.value
    const algorithm = String(meta.algorithm || '').trim()
    const confidence = String(meta.confidence || '').trim()
    const parts = ['本次记录为预测类历史产物']
    if (algorithm) parts.push(`算法：${algorithm}`)
    if (confidence) parts.push(`置信区间：${confidence}`)
    return `${parts.join('，')}。管理员可按普通查询详情查看原始问题、执行信息、结果快照与推理图谱。`
  }
  return '当前记录未生成摘要，建议结合原始问题、SQL 和审计日志一起查看。'
})

const detailParseDescription = computed(() => {
  const kind = advancedAnalysisKind(detail.value)
  if (kind === 'forecast') {
    return '本次请求命中预测流程，由时序算法基于历史序列直接产出预测快照，因此详情中不展示 SQL。'
  }
  if (kind === 'whatIf') {
    return '本次请求命中情景推演流程，由拟合算法或业务公式直接计算情景结果，因此详情中不展示 SQL。'
  }
  if (kind === 'alert') {
    return '本次请求命中智能预警流程，由阈值规则与异常检测生成预警配置或事件，因此详情中不展示 SQL。'
  }
  if (detailRealSql.value) {
    return '本次请求已落库生成 SQL，可继续结合审计日志追溯。'
  }
  return '当前记录未保存 SQL 文本，通常意味着解析未完成或流程被提前中断。'
})

const detailSqlStatusDescription = computed(() => {
  if (isForecastDetailSnapshot(detail.value) && !detailRealSql.value) {
    return '预测记录无 SQL 生成环节，但仍保留执行状态、耗时、快照和推理信息。'
  }
  if (detail.value?.sqlStatus === 'BLOCKED') return 'SQL 已生成，但在审计或权限校验阶段被拦截。'
  if (detail.value?.sqlStatus === 'READY') return 'SQL 已成功生成并进入执行链路。'
  return '当前记录没有完整 SQL 结果。'
})

const resultPreviewMetrics = computed(() => {
  const current = detail.value || {}
  const metrics = current.snapshotMetrics || {}
  if (isForecastDetailSnapshot(current)) {
    const snapshot = getDetailChartSnapshot(current)
    const meta = detailForecastMeta.value
    const algorithmParams = meta.algorithmParams && typeof meta.algorithmParams === 'object'
      ? meta.algorithmParams
      : {}
    const dataRows = Array.isArray(snapshot.data) ? snapshot.data : []
    const forecastCount = dataRows.filter(row =>
      row && typeof row === 'object' && (
        row.forecast != null ||
        row.upper != null ||
        row.lower != null ||
        String(row.phase || '').toLowerCase() === 'forecast'
      )
    ).length
    const historyCount = Math.max(0, dataRows.length - forecastCount)
    return [
      {
        label: '图表类型',
        value: chartTypeLabel(metrics.chartType || current.chartType),
        hint: '预测快照沿用普通查询的图表预览区域展示'
      },
      {
        label: '预测算法',
        value: meta.algorithm || algorithmParams.algorithm || '未记录',
        hint: '来自预测快照或保存方案的算法元信息'
      },
      {
        label: '样本/预测点',
        value: `${historyCount} / ${forecastCount}`,
        hint: '历史样本点数 / 预测点数'
      },
      {
        label: '方案版本',
        value: snapshot.advancedAnalysisPlanVersion ? `v${snapshot.advancedAnalysisPlanVersion}` : '未记录',
        hint: meta.confidence ? `置信区间 ${meta.confidence}` : '预测方案版本与置信区间'
      }
    ]
  }
  return [
    {
      label: '图表类型',
      value: chartTypeLabel(metrics.chartType || current.chartType),
      hint: '用于恢复这次查询的主结果样式'
    },
    {
      label: '结果样例',
      value: `${current.snapshotPreviewRows?.length || 0} 行`,
      hint: '详情中保留的结果样例行数'
    },
    {
      label: '图谱上下文',
      value: metrics.hasGraphContext ? '已命中' : '未命中',
      hint: metrics.hasGraphContext ? '生成过程中引用了知识图谱上下文' : '本次结果未附带图谱上下文'
    },
    {
      label: '快照来源',
      value: metrics.tableName || current.queryTableName || '未记录数据源',
      hint: '生成该图表快照时关联的数据范围'
    }
  ]
})

const reasoningDisplaySteps = computed(() => buildReasoningDisplaySteps(detail.value))

const reasoningIntroText = computed(() => (
  shouldShowDetailSql.value
    ? '按阶段回放模型如何理解问题、定位数据、生成 SQL 并形成结果'
    : '按阶段回放模型如何理解需求、定位数据、执行算法并形成结果'
))

const reasoningOverviewCards = computed(() => {
  const current = detail.value || {}
  const isAdvanced = detailIsAdvancedAnalysis.value
  return [
    {
      label: '分析目标',
      value: intentTypeLabel(current.intentType),
      hint: '模型对本次问句识别出的主要任务类型'
    },
    {
      label: '数据定位',
      value: current.queryTableName || current.permissionCheck?.scopeTableName || '未记录数据范围',
      hint: '本次生成过程中主要引用的数据表或数据源'
    },
    {
      label: isAdvanced ? '算法策略' : '解析引擎',
      value: detailParseEngineText.value,
      hint: aiParseResultText(current.aiParseResult, current.aiParseResultLabel)
    },
    {
      label: '结果产物',
      value: chartTypeLabel(current.chartType),
      hint: isAdvanced ? (advancedAnalysisModelLabel(current) || '算法产物') : (current.sqlStatusLabel || '未记录 SQL 状态')
    }
  ]
})

const auditSummaryCards = computed(() => {
  const summary = detail.value?.auditSummary || {}
  const auditCount = Number(summary.count || 0)
  const blockedCount = Number(summary.blockedCount || 0)
  const warnCount = Number(summary.warnCount || 0)
  const slowCount = Number(summary.slowCount || 0)
  return [
    {
      label: '审计记录',
      value: auditCount.toLocaleString('zh-CN'),
      status: auditCount > 0 ? '已记录' : '未记录',
      hint: '本轮查询保留的安全审计日志数量',
      tone: 'blue'
    },
    {
      label: '拦截',
      value: blockedCount.toLocaleString('zh-CN'),
      status: blockedCount > 0 ? '已拦截' : '未触发',
      hint: blockedCount > 0 ? '存在被权限或安全规则拦截的执行' : '未发现需要拦截的高风险操作',
      tone: blockedCount > 0 ? 'red' : 'neutral'
    },
    {
      label: '告警',
      value: warnCount.toLocaleString('zh-CN'),
      status: warnCount > 0 ? '需关注' : '无告警',
      hint: warnCount > 0 ? '存在安全或性能告警，建议查看审计明细' : '当前审计没有产生告警记录',
      tone: warnCount > 0 ? 'amber' : 'neutral'
    },
    {
      label: '慢查询',
      value: slowCount.toLocaleString('zh-CN'),
      status: slowCount > 0 ? '需优化' : '正常',
      hint: slowCount > 0 ? '执行耗时触发慢查询规则，可结合 SQL 与图谱定位' : '本轮查询未触发慢查询规则',
      tone: slowCount > 0 ? 'green' : 'neutral'
    }
  ]
})

const auditContextOverviewCards = computed(() => {
  const current = detail.value || {}
  const permission = current.permissionCheck || {}
  const cache = current.cacheContext || {}
  const latestLog = current.latestAuditLog || {}
  const sensitiveCount = Array.isArray(latestLog.sensitiveFieldItems) ? latestLog.sensitiveFieldItems.length : 0
  return [
    {
      label: '权限校验',
      value: permission.label || '未记录',
      hint: permission.scopeTableName || current.queryTableName
        ? `作用域：${permission.scopeTableName || current.queryTableName}`
        : '未记录授权作用域'
    },
    {
      label: '缓存状态',
      value: cache.cacheHitLabel || '未命中缓存',
      hint: cache.redisStatusText || '未记录缓存侧状态'
    },
    {
      label: '脱敏字段',
      value: sensitiveCount ? `${sensitiveCount} 个` : '未发现',
      hint: sensitiveCount ? '审计命中了敏感字段并保留明细' : '当前审计未发现敏感字段'
    },
    {
      label: '执行保护',
      value: cache.queryGuardActionLabel || '未触发',
      hint: cache.queryGuardActionLabel ? '执行前安全与性能保护动作' : '未记录执行保护动作'
    }
  ]
})

const conversationContext = computed(() => detail.value?.conversationContext || {})

const conversationTurns = computed(() => (
  Array.isArray(conversationContext.value.turns) ? conversationContext.value.turns : []
))

const conversationMessages = computed(() => conversationTurns.value)

const conversationContextNotice = computed(() => {
  const visible = Number(conversationContext.value.visibleTurns || conversationTurns.value.length || 0)
  const total = Number(conversationContext.value.totalTurns || visible)
  const limit = Number(conversationContext.value.contextLimit || visible)
  const parts = []
  if (conversationContext.value.hasEarlierContext) {
    parts.push(`已隐藏更早上下文，当前仅展示最近 ${visible || limit} 条消息`)
  }
  if (conversationContext.value.hasLaterTurns) {
    parts.push('当前记录之后的对话未参与本次生成，已不展示')
  }
  if (!parts.length) return ''
  return `${parts.join('；')}。会话共 ${total || visible} 条消息。`
})

const conversationRoleLabel = (role) => {
  const text = String(role || '').trim().toUpperCase()
  if (text === 'USER') return '用户'
  if (text === 'ASSISTANT') return '助手'
  if (text === 'SYSTEM') return '系统'
  return text || '消息'
}

const conversationMessageRole = (turn) => {
  const role = String(turn?.role || '').trim().toUpperCase()
  return role === 'USER' ? 'user' : 'system'
}

const isCurrentConversationTurn = (turn) => (
  Boolean(turn?.isCurrent) ||
  (Array.isArray(turn?.artifacts) && turn.artifacts.some(isCurrentConversationArtifact))
)

const isCurrentConversationPrompt = (turn) => Boolean(turn?.isCurrentPrompt)

const conversationTurnMarkerLabel = (turn) => {
  if (isCurrentConversationTurn(turn)) return '当前记录'
  if (isCurrentConversationPrompt(turn)) return '生成提问'
  return ''
}

const isCurrentConversationArtifact = (artifact) => (
  String(artifact?.historyId || '').trim() &&
  String(artifact?.historyId) === String(detail.value?.id)
)

const artifactTypeDisplay = (artifact) => {
  const type = String(artifact?.artifactType || '').trim().toUpperCase()
  if (type === 'SQL') return 'SQL'
  if (type === 'CHART') return '图表'
  if (type === 'ADVANCED_FORECAST') return '时序预测'
  if (type === 'ADVANCED_WHAT_IF') return '情景推演'
  if (type === 'ADVANCED_ALERT') return '智能预警'
  return artifact?.artifactTypeLabel || artifact?.artifactType || '产物'
}

const conversationThinkingLogs = (turn) => {
  const context = turn?.context && typeof turn.context === 'object' ? turn.context : {}
  const raw = context.reasoningReplaySteps || context.reasoningLogs || turn.reasoningReplaySteps || []
  if (!Array.isArray(raw)) return []
  return raw.map((item, index) => {
    if (typeof item === 'string') return item.trim()
    const title = String(item?.title || `步骤 ${index + 1}`).trim()
    const detailText = String(item?.detail || item?.text || item?.message || '').trim()
    return [title, detailText].filter(Boolean).join('：')
  }).filter(Boolean)
}

const graphVisual = computed(() => buildGraphVisual(detail.value))
const graphVisualNodes = computed(() => graphVisual.value.nodes)
const graphVisualEdges = computed(() => graphVisual.value.edges)
const fullGraphVisual = computed(() => buildGraphVisual(detail.value, {
  maxNodes: 32,
  maxEdges: 60,
  width: 1120,
  height: 620,
  radiusX: 430,
  radiusY: 230,
  labelMax: 12
}))
const fullGraphRelations = computed(() => fullGraphVisual.value.edges.map(edge => ({
  id: edge.id,
  fromLabel: edge.from.label,
  toLabel: edge.to.label,
  label: edge.label
})))

const openFullGraphDialog = () => {
  fullGraphVisible.value = true
}

const graphNodeId = (node, index = 0) => {
  if (!node || typeof node === 'string') return `node-${index}-${String(node || '').slice(0, 24)}`
  return String(node.nodeKey || node.id || node.sourceId || node.field || node.fieldName || node.label || node.name || `node-${index}`).trim()
}

const normalizeGraphType = (node) => {
  const type = String(node?.nodeType || node?.type || '').trim().toUpperCase()
  if (['UPLOAD_TABLE', 'OFFICIAL_TABLE', 'TABLE', 'DATASOURCE'].includes(type)) return 'table'
  if (type === 'FIELD') return 'field'
  if (type === 'BUSINESS_METRIC') return 'metric'
  if (type.includes('FOREIGN_KEY') || type.includes('RELATION')) return 'relation'
  return 'context'
}

const graphRelationLabel = (value) => {
  const type = String(value || '').trim().toUpperCase()
  if (type === 'HAS_FIELD') return '包含字段'
  if (type === 'HAS_METRIC') return '定义指标'
  if (type === 'USES_FIELD') return '使用字段'
  if (type === 'REFERENCES_FIELD') return '引用字段'
  if (type === 'REFERENCES_TABLE') return '关联表'
  if (type === 'MARKED_AS') return '标记为'
  if (type === 'FOREIGN_KEY_FROM') return '外键来源'
  if (type === 'FOREIGN_KEY_TO') return '外键指向'
  return type || '关联'
}

const graphNodeTypeRank = (node) => {
  const kind = normalizeGraphType(node)
  if (kind === 'table') return 0
  if (kind === 'metric') return 1
  if (kind === 'field') return 2
  if (kind === 'relation') return 3
  return 4
}

const shortGraphLabel = (text, max = 10) => {
  const value = String(text || '').trim()
  return value.length > max ? `${value.slice(0, max)}...` : value
}

const buildGraphFallbackEdges = (nodes) => {
  const tableNodes = nodes.filter(node => normalizeGraphType(node) === 'table')
  const fieldNodes = nodes.filter(node => normalizeGraphType(node) === 'field')
  const metricNodes = nodes.filter(node => normalizeGraphType(node) === 'metric')
  const edges = []
  const tableBySource = new Map()
  tableNodes.forEach(node => {
    const key = String(node.tableName || node.sourceId || node.nodeKey || '').split('.')[0]
    if (key) tableBySource.set(key, node)
  })
  const firstTable = tableNodes[0]
  fieldNodes.forEach(node => {
    const source = String(node.tableName || node.sourceId || '').split('.')[0]
    const table = tableBySource.get(source) || firstTable
    if (table) {
      edges.push({
        fromKey: graphNodeId(table),
        toKey: graphNodeId(node),
        relationType: 'HAS_FIELD',
        inferred: true
      })
    }
  })
  metricNodes.forEach(node => {
    const source = String(node.tableName || node.sourceId || '').split('.')[0]
    const table = tableBySource.get(source) || firstTable
    if (table) {
      edges.push({
        fromKey: graphNodeId(table),
        toKey: graphNodeId(node),
        relationType: 'HAS_METRIC',
        inferred: true
      })
    }
    const field = String(node.field || node.fieldName || '').trim()
    if (field) {
      const matchedField = fieldNodes.find(item => {
        const id = String(item.sourceId || item.field || item.fieldName || item.nodeKey || '').toLowerCase()
        return id.endsWith(`.${field.toLowerCase()}`) || id.includes(`:field:${field.toLowerCase()}`)
      })
      if (matchedField) {
        edges.push({
          fromKey: graphNodeId(node),
          toKey: graphNodeId(matchedField),
          relationType: 'USES_FIELD',
          inferred: true
        })
      }
    }
  })
  return edges
}

const buildGraphVisual = (currentDetail, options = {}) => {
  const maxNodes = Number(options.maxNodes || 12)
  const maxEdges = Number(options.maxEdges || 18)
  const width = Number(options.width || 720)
  const height = Number(options.height || 360)
  const centerX = Math.round(width / 2)
  const centerY = Math.round(height / 2)
  const graphPath = currentDetail?.graphPath && typeof currentDetail.graphPath === 'object' ? currentDetail.graphPath : {}
  const rawNodes = Array.isArray(graphPath.nodes) && graphPath.nodes.length
    ? graphPath.nodes
    : (Array.isArray(currentDetail?.graphContext) ? currentDetail.graphContext : [])
  const normalizedNodes = rawNodes
    .filter(node => node && typeof node === 'object')
    .map((node, index) => ({
      ...node,
      _id: graphNodeId(node, index)
    }))
  const seen = new Set()
  const nodes = normalizedNodes.filter(node => {
    if (seen.has(node._id)) return false
    seen.add(node._id)
    return true
  }).sort((a, b) => graphNodeTypeRank(a) - graphNodeTypeRank(b))
  const limitedNodes = nodes.slice(0, maxNodes)
  const nodeIdSet = new Set(limitedNodes.map(node => node._id))
  let rawEdges = Array.isArray(graphPath.edges) && graphPath.edges.length
    ? graphPath.edges
    : buildGraphFallbackEdges(limitedNodes)
  const nodeCount = limitedNodes.length
  const radiusX = Number(options.radiusX || (nodeCount <= 4 ? 190 : 260))
  const radiusY = Number(options.radiusY || (nodeCount <= 4 ? 92 : 125))
  const labelMax = Number(options.labelMax || 8)
  const visualNodes = limitedNodes.map((node, index) => {
    const angle = nodeCount <= 1 ? -Math.PI / 2 : (-Math.PI / 2) + (Math.PI * 2 * index / nodeCount)
    const kind = normalizeGraphType(node)
    return {
      id: node._id,
      x: Math.round(centerX + Math.cos(angle) * radiusX),
      y: Math.round(centerY + Math.sin(angle) * radiusY),
      radius: kind === 'table' ? 42 : kind === 'metric' ? 38 : 34,
      kind,
      label: formatGraphNodeTitle(node, index),
      shortLabel: shortGraphLabel(formatGraphNodeTitle(node, index), kind === 'table' ? Math.max(9, labelMax) : labelMax),
      typeLabel: formatGraphNodeType(node)
    }
  })
  const visualNodeMap = new Map(visualNodes.map(node => [node.id, node]))
  let visualEdges = rawEdges
    .map((edge, index) => {
      const fromKey = String(edge.fromKey || edge.source || edge.sourceKey || edge.from || '').trim()
      const toKey = String(edge.toKey || edge.target || edge.targetKey || edge.to || '').trim()
      if (!nodeIdSet.has(fromKey) || !nodeIdSet.has(toKey) || fromKey === toKey) return null
      const from = visualNodeMap.get(fromKey)
      const to = visualNodeMap.get(toKey)
      if (!from || !to) return null
      return {
        id: `${fromKey}-${toKey}-${edge.relationType || index}`,
        from,
        to,
        label: graphRelationLabel(edge.relationType),
        labelX: Math.round((from.x + to.x) / 2),
        labelY: Math.round((from.y + to.y) / 2 - 6)
      }
    })
    .filter(Boolean)
    .slice(0, maxEdges)
  if (!visualEdges.length && limitedNodes.length > 1) {
    rawEdges = buildGraphFallbackEdges(limitedNodes)
    visualEdges = rawEdges
      .map((edge, index) => {
        const fromKey = String(edge.fromKey || '').trim()
        const toKey = String(edge.toKey || '').trim()
        const from = visualNodeMap.get(fromKey)
        const to = visualNodeMap.get(toKey)
        if (!from || !to || fromKey === toKey) return null
        return {
          id: `${fromKey}-${toKey}-fallback-${index}`,
          from,
          to,
          label: graphRelationLabel(edge.relationType),
          labelX: Math.round((from.x + to.x) / 2),
          labelY: Math.round((from.y + to.y) / 2 - 6)
        }
      })
      .filter(Boolean)
      .slice(0, maxEdges)
  }
  return { nodes: visualNodes, edges: visualEdges }
}

const formatGraphNodeTitle = (item, index = 0) => {
  if (typeof item === 'string') return item
  return String(item?.label || item?.name || item?.title || item?.nodeKey || item?.sourceId || `节点 ${index + 1}`).trim()
}

const formatGraphNodeType = (item) => {
  if (!item || typeof item === 'string') return '上下文'
  const rawType = String(item?.nodeType || item?.type || '').trim()
  if (!rawType) return '上下文'
  if (rawType === 'UPLOAD_TABLE') return '数据表'
  if (rawType === 'FIELD') return '字段'
  if (rawType === 'BUSINESS_METRIC') return '业务指标'
  return rawType
}

const formatGraphNodeSource = (item) => {
  if (!item || typeof item === 'string') return ''
  const nodeType = String(item.nodeType || item.type || '').trim()
  if (nodeType === 'FIELD') return ''
  const parts = []
  const tableName = String(item.tableName || '').trim()
  if (tableName) parts.push(`表：${tableName}`)
  const type = nodeType
  if (type && type !== 'FIELD' && type !== 'UPLOAD_TABLE' && type !== 'BUSINESS_METRIC') {
    parts.push(`类型：${type}`)
  }
  return parts.join(' · ')
}

const formatGraphNodeContent = (item) => {
  if (typeof item === 'string') return item
  const label = String(item?.label || item?.name || item?.title || '').trim()
  const content = String(item?.content || item?.description || item?.summary || '').trim()
  const formula = String(item?.formula || '').trim()
  const field = String(item?.field || item?.fieldName || item?.fieldKey || '').trim()
  const fieldType = String(item?.fieldType || item?.dataType || '').trim()
  const sensitive = item?.sensitive
  const hasFormula = Boolean(formula)
  const hasField = Boolean(field)
  const hasContent = Boolean(content)
  if (item?.nodeType === 'FIELD' || item?.type === 'FIELD') {
    const details = []
    if (fieldType) details.push(`类型：${fieldType}`)
    if (sensitive !== null && sensitive !== undefined && sensitive !== '') details.push(`敏感：${sensitive === true || String(sensitive).toLowerCase() === 'true' ? '是' : '否'}`)
    return details.join(' · ') || '字段信息已保留'
  }
  if (item?.nodeType === 'BUSINESS_METRIC' || item?.type === 'BUSINESS_METRIC') {
    const details = []
    if (formula) details.push(`公式：${formula}`)
    if (field) details.push(`字段：${field}`)
    return details.join(' · ') || '业务指标已保留'
  }
  if (item?.nodeType === 'UPLOAD_TABLE' || item?.type === 'UPLOAD_TABLE') {
    const details = []
    if (content) details.push(content.replace(/\s+/g, ' '))
    if (fieldType) details.push(`类型：${fieldType}`)
    return details.join(' · ') || '数据表信息已保留'
  }
  if (hasContent) return content
  if (hasFormula || hasField) {
    return [formula ? `公式：${formula}` : '', field ? `字段：${field}` : ''].filter(Boolean).join(' · ')
  }
  if (label) return label
  return '暂无详细内容'
}

const routeAudit = computed(() => analytics.value?.routeAudit || {})

const routeAuditSummary = computed(() => routeAudit.value?.summary || {})

const hasRouteAuditData = computed(() => Number(routeAuditSummary.value.totalRouted || 0) > 0)

const routeAuditOverviewCards = computed(() => {
  const summary = routeAuditSummary.value
  const total = Number(summary.totalRouted || 0)
  return [
    {
      label: '路由样本',
      value: total.toLocaleString('zh-CN'),
      hint: `采样 ${Number(routeAudit.value.sampledRows || 0).toLocaleString('zh-CN')} 条历史`,
      tone: 'blue'
    },
    {
      label: '兜底率',
      value: formatPercent(summary.fallbackRate),
      hint: `${Number(summary.fallbackCount || 0).toLocaleString('zh-CN')} 次使用规则兜底`,
      tone: Number(summary.fallbackRate || 0) > 20 ? 'amber' : 'green'
    },
    {
      label: '澄清率',
      value: formatPercent(summary.clarificationRate),
      hint: `${Number(summary.clarificationCount || 0).toLocaleString('zh-CN')} 次需要补充参数`,
      tone: Number(summary.clarificationRate || 0) > 20 ? 'amber' : 'blue'
    },
    {
      label: '失败率',
      value: formatPercent(summary.failureRate),
      hint: `平均耗时 ${summary.avgDurationMs || 0} ms`,
      tone: Number(summary.failureRate || 0) > 0 ? 'red' : 'green'
    }
  ]
})

const routeIntentGroups = computed(() => (
  Array.isArray(routeAudit.value.intentGroups) ? routeAudit.value.intentGroups.slice(0, 5) : []
))

const routeExecutorGroups = computed(() => (
  Array.isArray(routeAudit.value.executorGroups) ? routeAudit.value.executorGroups.slice(0, 5) : []
))

const routeProblemSamples = computed(() => {
  const samples = Array.isArray(routeAudit.value.problemSamples) ? routeAudit.value.problemSamples : []
  if (samples.length) return samples.slice(0, 6)
  return Array.isArray(routeAudit.value.lowConfidenceExamples)
    ? routeAudit.value.lowConfidenceExamples.slice(0, 6)
    : []
})

const routeAuditIntentLabel = (value) => intentTypeLabel(value)

const routeAuditExecutorLabel = (value) => {
  const text = String(value || '').trim()
  const normalized = text.toLowerCase()
  const labels = {
    'multi-step-orchestrator': '多步骤任务编排器',
    'chat-bi-sql': '数据查询执行器',
    'advanced-analysis-forecast': '时序预测执行器',
    'alert-rule-draft': '预警规则生成器',
    'what-if-draft': '情景推演生成器',
    'business-model-agent': '业务模型代理',
    'dashboard-service': '看板服务',
    clarification: '需求澄清',
    'smart-chat-router': '智能路由器',
    'python-ai-service': '大模型解析服务',
    'redis-semantic-cache': '语义缓存复用',
    'java-fallback': '规则兜底解析',
    'java-fallback-ai-parse': '大模型失败后规则兜底',
    'java-fallback-exec-retry': '执行失败后规则重试',
    'java-federal-join': '联邦关联直连'
  }
  return labels[normalized] || normalizeModelCategoryText(text) || text || '未记录执行器'
}

const routeAuditOutcomeLabel = (value) => {
  const text = String(value || '').trim().toUpperCase()
  const labels = {
    COMPLETED: '已完成',
    FAILED: '执行失败',
    PARTIAL_FAILED: '部分失败',
    NEEDS_INPUT: '待补充参数',
    NEEDS_CONFIRMATION: '待确认',
    PARTIAL: '部分完成',
    UNKNOWN: '未记录状态'
  }
  return labels[text] || text || '未记录状态'
}

const routeOutcomeTagType = (value) => {
  const text = String(value || '').trim().toUpperCase()
  if (['FAILED', 'PARTIAL_FAILED'].includes(text)) return 'danger'
  if (['NEEDS_INPUT', 'NEEDS_CONFIRMATION', 'PARTIAL'].includes(text)) return 'warning'
  if (text === 'COMPLETED') return 'success'
  return 'info'
}

const formatRouteConfidence = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '未知'
  return number <= 1 ? `${Math.round(number * 100)}%` : `${number.toFixed(1)}%`
}

const formatRouteProblemHint = (item) => {
  const parts = []
  if (item.fallbackUsed) parts.push('已兜底')
  if (item.confidence !== null && item.confidence !== undefined) {
    parts.push(`置信度 ${formatRouteConfidence(item.confidence)}`)
  }
  if (Array.isArray(item.missingSlots) && item.missingSlots.length) {
    parts.push(`缺少 ${item.missingSlots.join('、')}`)
  }
  if (item.failureReason) parts.push(item.failureReason)
  if (!parts.length) parts.push(routeAuditExecutorLabel(item.chosenExecutor))
  return parts.join(' · ')
}

const queryTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.queryVolume, 'totalCount'))
const riskTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.riskVolume, 'riskCount'))
const cacheTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.cacheVolume, 'hitCount'))
const slowTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.slowVolume, 'slowCount'))
const detailHeroSummary = computed(() => {
  const current = detail.value || {}
  return current.summaryText || current.riskReason || '本次查询已完成执行，未记录额外风险原因。'
})
const sideSlowQueries = computed(() => {
  const groups = analytics.value?.performance?.slowQueryGroups
  if (Array.isArray(groups) && groups.length) return groups.slice(0, 8)
  return rows.value.filter(item => item.slowQuery).slice(0, 8)
})
const sideCacheMissGroups = computed(() => {
  const groups = analytics.value?.performance?.cacheMissGroups
  if (Array.isArray(groups) && groups.length) return groups.slice(0, 8)
  return rows.value.filter(item => !item.isHitCache).slice(0, 8)
})
const heroDescription = computed(() => {
  const policyText = String(governance.value?.policyText || '').trim()
  return policyText
    ? `集中追踪全平台查询、SQL 生成、模型解析、风险审计与执行性能。${policyText}`
    : '集中追踪全平台查询、SQL 生成、模型解析、风险审计与执行性能。'
})

const traceLabelMap = {
  activeTable: '当前数据表',
  selectedModel: '模型选择',
  modelId: '业务模型编号',
  modelName: '业务模型',
  modelVersion: '模型版本',
  source: '来源',
  businessModel: '业务模型',
  businessSemanticContext: '业务语义上下文',
  businessSemanticPlan: '业务语义匹配',
  businessSemanticGuard: '业务语义校验',
  businessSemanticGuardRetry: '业务语义重试校验',
  semanticSqlGuard: '语义 SQL 保护',
  fieldMappingAligned: '字段映射对齐',
  detailTableRequery: '明细表格重查',
  autoForecast: '自动预测',
  federalJoin: '联邦关联',
  sourceType: '数据源类型',
  tableName: '数据表',
  notExecuted: '是否未执行',
  changed: '是否调整',
  explicit: '是否显式指定',
  mode: '排序方式',
  direction: '排序方向',
  dimensionExpr: '维度表达式',
  metricExpr: '指标表达式',
  limit: '返回条数',
  points: '数据点数',
  algorithm: '预测算法',
  from: '调整前',
  to: '调整后',
  filter: '过滤字段',
  values: '过滤值',
  bindings: '绑定关系',
  fromFilter: '来源过滤',
  version: '模型版本',
  semanticCache: '语义缓存',
  cacheAudit: '缓存审计',
  kgContextNodes: '图谱上下文节点数',
  kgSync: '图谱同步',
  nl2sqlEngine: 'SQL 生成引擎',
  reason: '回退原因',
  queryGuard: '查询保护',
  timeoutMs: '超时时间',
  maxRows: '最大返回行数',
  maxScanRows: '最大扫描行数',
  limitInjected: '是否注入限制',
  explainRows: 'Explain 估算行数',
  explainBlocked: 'Explain 阻断',
  selectStar: '全字段查询',
  executeStatus: '执行状态',
  durationMs: '执行耗时',
  error: '错误信息',
  nodes: '命中节点数',
  labels: '命中标签',
  hintKeys: '映射提示来源'
}

const traceValueMap = {
  HIT: '命中',
  MISS: '未命中',
  REJECTED: '拒绝使用',
  LOADED: '已加载',
  EMPTY: '未加载',
  MATCHED: '已匹配',
  NO_MATCH: '未匹配',
  APPLIED: '已应用',
  SAFE: '安全',
  WARN: '告警',
  BLOCKED: '拦截',
  ALLOW: '允许',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  EMPTY_RESULT: '空结果',
  SKIPPED: '已跳过',
  BUSINESS_MODEL_SQL_VALIDATED: '业务模型 SQL 已校验',
  BUSINESS_MODEL_SQL_REBUILD: '已按业务模型重建 SQL',
  SORT_INTENT: '排序意图',
  TOP_N_INTENT: '前 N 名意图',
  GEO_VALUE_DATA_PROFILE: '地理值数据画像',
  VALUE_FILTER_DIMENSION_CONSISTENCY: '筛选值与维度一致性',
  MACRO_REGION_FIELD_CORRECTION: '大区字段修正',
  MACRO_REGION_FILTER_WITH_PROVINCE_DIMENSION: '按大区过滤并使用省份维度',
  NAME_ASC: '名称升序',
  NAME_DESC: '名称降序',
  VALUE_ASC: '数值升序',
  VALUE_DESC: '数值降序',
  ASC: '升序',
  DESC: '降序',
  ORDER_LIMIT_REWRITE: '重写排序和条数限制',
  REBUILD_AGGREGATION: '重建聚合查询',
  DETAIL_INTENT: '明细查询意图',
  NO_EXPLICIT_FORECAST_INTENT: '未明确要求预测',
  PREFER_REAL_SOURCE_FORECAST: '建议基于原始数据重新预测',
  NON_TEMPORAL_SERIES: '结果不是时序序列',
  INSUFFICIENT_POINTS: '数据点不足',
  ALL_ZERO_SERIES: '序列全为 0',
  UNKNOWN_METRIC: '未识别指标',
  ADVANCED_SERVICE_UNAVAILABLE: '高级分析服务不可用',
  EMPTY_FORECAST_SERIES: '预测序列为空',
  NO_FORECAST_VALUES: '没有可用预测值',
  SMART_QUERY_INTENT: '智能查询意图',
  AI_RESPONSE_INVALID: '大模型返回内容不可用',
  AI_UNAVAILABLE: '大模型服务不可用',
  AI_SQL_EXEC_FAILED: '大模型 SQL 执行失败',
  default: '默认模型',
  activeBusinessModel: '当前业务模型',
  tableDefaultModel: '数据表默认模型',
  dimensionSystem: '维度识别系统',
  'python-ai-service': '大模型解析服务',
  'redis-semantic-cache': '语义缓存复用',
  'java-fallback': '规则兜底解析',
  'java-fallback-ai-parse': '大模型解析失败后规则兜底',
  'java-fallback-exec-retry': '执行失败后规则重试',
  'java-federal-join': '联邦关联直连',
  triggered: '已触发',
  direct: '直接执行',
  FEDERAL_JOIN: '联邦关联数据源',
  bar: '柱状图',
  line: '折线图',
  pie: '饼图',
  table: '表格',
  true: '是',
  false: '否',
  LOCAL: '本地',
  null: '-',
  undefined: '-'
}

const auditRuleLabelMap = {
  AST_PARSE: 'SQL 解析失败拦截',
  ONLY_SELECT: '仅允许 SELECT',
  TABLE_SCOPE: '越权表访问拦截',
  DANGEROUS_KEYWORD: '危险关键字拦截',
  MULTI_STATEMENT: '多语句拦截',
  SYSTEM_TABLE_BLOCK: '系统表访问拦截',
  LIMIT_REQUIRED: '结果集需限制行数',
  NO_SELECT_STAR: '避免 SELECT *',
  SENSITIVE_FIELD: '命中敏感字段',
  SLOW_QUERY: '慢查询告警',
  SLOW_QUERY_BREAKER: '慢查询熔断',
  MAX_SCAN_ROWS: '扫描行数超阈值'
}

const tokenKeys = new Set(['labels', 'hintKeys'])
const wideKeys = new Set(['activeTable', 'queryGuard', 'error', 'labels', 'hintKeys', 'metricExpr', 'bindings'])

const traceTokenMap = {
  dictionaryMatches: '词典匹配',
  recommendedMapping: '推荐映射',
  ambiguities: '歧义字段',
  formulaCandidates: '公式候选',
  graphReasoning: '图谱推理',
  fieldCandidates: '字段候选',
  businessSemanticTrace: '业务语义轨迹'
}

const traceUnitKeys = {
  timeoutMs: 'ms',
  maxRows: '行',
  maxScanRows: '行',
  explainRows: '行',
  durationMs: 'ms',
  kgContextNodes: '个',
  nodes: '个',
  limit: '行',
  points: '个'
}

const traceObjectLabelMap = {
  enabled: '启用状态',
  modelId: '模型编号',
  modelName: '模型名称',
  modelVersion: '模型版本',
  source: '来源',
  matchedMetric: '匹配指标',
  resolvedMetricField: '指标字段',
  metricColumn: '指标列',
  formulaApplied: '是否套用公式',
  metricSource: '指标来源',
  dictionaryMatched: '词典命中',
  dictionaryHitTerm: '命中词条',
  matchedDimension: '匹配维度',
  resolvedDimensionField: '解析维度字段',
  dimensionColumn: '维度字段',
  dimensionSource: '维度来源',
  finalSqlValidated: 'SQL 终检',
  sqlRebuilt: '是否重建 SQL',
  analysisSemanticValidated: '分析语义校验',
  analysisMetricField: '分析指标字段',
  analysisMetricLabel: '分析指标名称',
  analysisMetricExpression: '分析指标表达式',
  analysisFormula: '分析公式'
}

const formatTraceLabel = (key) => traceLabelMap[key] || traceObjectLabelMap[key] || '补充信息'

const formatTraceToken = (token) => traceTokenMap[token] || token

const formatTraceValue = (value, key = '') => {
  const text = String(value ?? '').trim()
  if (!text) return '-'
  const mapped = traceValueMap[text] || traceValueMap[text.toUpperCase()] || traceValueMap[text.toLowerCase()]
  const normalized = mapped || text
  const unit = traceUnitKeys[key]
  if (unit && /^-?\d+(\.\d+)?$/.test(text)) {
    return `${Number(text).toLocaleString('zh-CN')} ${unit}`
  }
  if (key === 'modelId' && /^-?\d+$/.test(text)) {
    return `#${text}`
  }
  if (key === 'selectedModel' && /^-?\d+$/.test(text)) {
    return `业务模型 #${text}`
  }
  if (key === 'bindings' && text.includes('->')) {
    return text.split(',').map(item => item.trim().replace('->', ' → ')).filter(Boolean).join('，')
  }
  if (key === 'values') {
    return text.split(',').map(item => item.trim()).filter(Boolean).join('，') || '-'
  }
  return normalized
}

const splitTraceValueLines = (value) => String(value || '')
  .split('；')
  .map(item => item.trim())
  .filter(Boolean)

const createTraceItem = ({ id, key, label, value, tokens = [], wide = false }) => {
  const normalizedValue = String(value ?? '').trim() || '-'
  const valueLines = tokens.length ? [] : splitTraceValueLines(normalizedValue)
  return {
    id,
    key,
    label,
    value: normalizedValue,
    tokens,
    valueLines,
    wide: wide || valueLines.length > 2 || normalizedValue.length > 56
  }
}

const splitTraceTokens = (key, value) => {
  if (!tokenKeys.has(key)) return []
  return String(value || '')
    .split(key === 'labels' ? '|' : ',')
    .map(item => item.trim())
    .map(formatTraceToken)
    .filter(Boolean)
}

const formatCompoundValue = (value) => {
  const parts = String(value || '')
    .split(';')
    .map(item => item.trim())
    .filter(Boolean)
  if (parts.length <= 1) return ''
  return parts.map((part) => {
    const [rawKey, ...rest] = part.split('=')
    const nestedKey = rawKey?.trim()
    const nestedValue = rest.join('=').trim()
    if (!nestedKey || !rest.length) return formatTraceValue(part)
    return `${formatTraceLabel(nestedKey)}：${formatTraceValue(nestedValue, nestedKey)}`
  }).join('；')
}

const parseTraceObjectText = (text) => {
  const body = String(text || '').trim().replace(/^\{/, '').replace(/\}$/, '')
  if (!body) return {}
  return body.split(',')
    .map(item => item.trim())
    .filter(Boolean)
    .reduce((acc, part) => {
      const [rawKey, ...rest] = part.split('=')
      const key = rawKey?.trim()
      const value = rest.join('=').trim()
      if (key) acc[key] = value
      return acc
    }, {})
}

const summarizeTraceObject = (text) => {
  const payload = parseTraceObjectText(text)
  const preferredKeys = [
    'enabled',
    'modelName',
    'modelId',
    'matchedMetric',
    'resolvedMetricField',
    'metricColumn',
    'formulaApplied',
    'matchedDimension',
    'resolvedDimensionField',
    'dimensionColumn',
    'dictionaryMatched',
    'dictionaryHitTerm',
    'source',
    'modelVersion',
    'finalSqlValidated',
    'sqlRebuilt'
  ]
  const orderedKeys = [
    ...preferredKeys,
    ...Object.keys(payload).filter(key => !preferredKeys.includes(key))
  ]
  const parts = orderedKeys
    .filter(key => payload[key] !== undefined && payload[key] !== '')
    .map(key => `${formatTraceLabel(key)}：${formatTraceValue(payload[key], key)}`)
  return parts.length ? parts.join('；') : '已保留业务语义明细'
}

const parseTraceLine = (line, prefix) => {
  const trimmed = String(line || '').trim()
  if (!trimmed) return []
  const parts = trimmed
    .split(';')
    .map(part => part.trim())
    .filter(Boolean)
  const items = []
  parts.forEach((part, index) => {
      if (part.startsWith('{')) {
        items.push(createTraceItem({
          id: `${prefix}-trace-object-${index}`,
          key: 'traceObject',
          label: '业务语义明细',
          value: summarizeTraceObject(part),
          tokens: [],
          wide: true
        }))
        return
      }
      const [rawKey, ...rest] = part.split('=')
      const key = rawKey?.trim()
      const value = rest.join('=').trim()
      if (!key) return
      if (!rest.length) {
        items.push(createTraceItem({
          id: `${prefix}-note-${index}`,
          key: 'traceNote',
          label: '补充说明',
          value: formatTraceValue(part),
          tokens: [],
          wide: true
        }))
        return
      }
      const tokens = splitTraceTokens(key, value)
      const compoundValue = tokens.length ? '' : formatCompoundValue(value)
      items.push(createTraceItem({
        id: `${prefix}-${key}-${index}`,
        key,
        label: formatTraceLabel(key),
        value: compoundValue || formatTraceValue(value, key),
        tokens,
        wide: wideKeys.has(key) || tokens.length > 4
      }))
    })
  return items
}

const parseTraceText = (text, prefix) => String(text || '')
  .split('\n')
  .flatMap((line, lineIndex) => parseTraceLine(line, `${prefix}-${lineIndex}`))

const splitCsvList = (text) => String(text || '')
  .split(',')
  .map(item => item.trim())
  .filter(item => item && item !== '-' && item !== 'null' && item !== 'undefined')

const mapAuditRuleLabel = (rule) => auditRuleLabelMap[rule] || rule

const sourceTypeLabel = (value) => {
  if (value === 'OFFICIAL') return '官方数据源'
  if (value === 'UPLOAD') return '上传数据'
  return value || '未知数据源'
}

const chartTypeLabel = (value) => ({
  bar: '柱状图',
  line: '折线图',
  pie: '饼图',
  table: '表格',
  alert: '智能预警',
  advancedalert: '智能预警',
  advanced_alert: '智能预警',
  advancedanalysis: '高级分析',
  advanced_analysis: '高级分析',
  advancedforecast: '时序预测',
  advanced_forecast: '时序预测',
  advancedwhatif: '情景推演',
  advanced_what_if: '情景推演'
})[String(value || '').toLowerCase()] || (value || '未识别图表')

const historyResultTypeLabel = (entry = {}) => {
  const advancedKind = advancedAnalysisKind(entry) || firstAdvancedAnalysisType(entry?.artifactType, entry?.intentType, entry?.chartType)
  if (advancedKind) return advancedAnalysisTypeLabel(advancedKind)
  const artifactType = String(entry?.artifactType || '').trim().toUpperCase()
  if (artifactType === 'SQL') return 'SQL'
  if (artifactType === 'TABLE') return '表格'
  if (artifactType === 'TEXT') return '文本'
  if (artifactType === 'REPORT') return '报告'
  return chartTypeLabel(entry?.chartType)
}

const formatDuration = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  return `${value} ms`
}

const formatPreviewValue = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  if (Array.isArray(value)) {
    const parts = value.map(item => formatPreviewValue(item)).filter(item => item && item !== '-')
    return parts.length ? parts.join('，') : '-'
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return '[复杂对象]'
    }
  }
  return String(value)
}

const summarizeFieldMapping = (mapping) => {
  if (!mapping || typeof mapping !== 'object') return []
  return [
    { label: '维度', value: String(mapping.dimension || mapping.dimensionKey || '').trim() },
    { label: '指标', value: String(mapping.metric || mapping.metricKey || '').trim() },
    { label: '维度字段', value: String(mapping.dimensionKey || '').trim() },
    { label: '指标字段', value: String(mapping.metricKey || '').trim() }
  ].filter(item => item.value)
}

const reasoningStageCatalog = {
  understanding: { label: '需求理解', type: 'primary', hint: '先理解用户到底想看什么、怎么展示结果' },
  dataset: { label: '数据定位', type: 'success', hint: '确定本次分析对应的数据范围、表和字段语义' },
  cache: { label: '缓存判断', type: 'success', hint: '先检查能否复用历史相似问题的理解结果' },
  graph: { label: '上下文补充', type: 'info', hint: '引入知识图谱和业务语义，补全字段与口径理解' },
  sql: { label: 'SQL 生成', type: 'warning', hint: '把自然语言理解结果转换成可执行 SQL' },
  guard: { label: '执行校验', type: 'danger', hint: '执行前补充权限、安全和性能保护' },
  result: { label: '结果返回', type: 'info', hint: '汇总执行结果、耗时与异常信息' },
  record: { label: '过程记录', type: 'info', hint: '保留关键痕迹，便于管理员回放分析过程' }
}

const toReasoningNumber = (value) => {
  const parsed = Number(String(value ?? '').replace(/,/g, '').trim())
  return Number.isFinite(parsed) ? parsed : Number.NaN
}

const formatReasoningCount = (value) => {
  const parsed = toReasoningNumber(value)
  return Number.isFinite(parsed) ? parsed.toLocaleString('zh-CN') : String(value || '-')
}

const isTruthyReasoningValue = (value) => {
  const text = String(value ?? '').trim().toLowerCase()
  return text === 'true' || text === '1' || text === 'yes'
}

const splitReasoningTokens = (value) => String(value || '')
  .split(/[|,]/)
  .map(item => item.trim())
  .filter(Boolean)

const describeReasoningEngine = (value) => {
  const text = String(value || '').trim()
  const lower = text.toLowerCase()
  if (!text) return '未记录的解析引擎'
  if (lower === 'redis-semantic-cache') return '语义缓存复用结果'
  if (lower === 'python-ai-service') return '大模型解析服务'
  if (lower === 'java-fallback') return '规则兜底解析'
  if (lower === 'java-federal-join') return '联邦关联直连'
  if (lower === 'manual-submit') return '人工提交审计'
  return text
}

const describeReasoningTable = (value, currentDetail) => {
  const text = String(value || currentDetail?.queryTableName || '').trim()
  if (!text) return '未记录具体数据表'
  if (text.startsWith('official:')) {
    const parts = text.split(':')
    return `官方数据源表「${parts[2] || text}」`
  }
  return `数据表「${text}」`
}

const sequenceLead = (index, total) => {
  if (index === 0) return '首先，'
  if (index === total - 1) return '最后，'
  if (index === 1) return '随后，'
  return '接着，'
}

const reasoningNarratorMap = {
  activeTable: {
    title: '确认分析范围',
    stage: 'dataset',
    narrate: (value, currentDetail) => `系统先将本次分析范围锁定在${describeReasoningTable(value, currentDetail)}上。`
  },
  semanticCache: {
    title: '检查语义缓存',
    stage: 'cache',
    narrate: (value) => {
      const text = String(value || '').trim().toUpperCase()
      if (text === 'HIT') {
        return '系统先检查语义缓存，命中了与当前问题相似的历史理解结果，因此优先复用已有推理。'
      }
      if (text === 'MISS') {
        return '系统先检查语义缓存，但没有命中可直接复用的历史理解结果，需要继续实时推理。'
      }
      if (text === 'REJECTED') {
        return '系统检查过语义缓存，但当前缓存结果不满足复用条件，因此继续走实时解析流程。'
      }
      return `系统记录了本次语义缓存判断结果为“${formatTraceValue(value)}”。`
    }
  },
  cacheAudit: {
    title: '确认缓存可用性',
    stage: 'cache',
    narrate: (value) => `缓存复用结果经过审计校验，本次审计结论为“${formatTraceValue(value)}”。`
  },
  kgContextNodes: {
    title: '补充图谱上下文',
    stage: 'graph',
    narrate: (value) => `为了补充业务语义，系统从知识图谱中取回 ${formatReasoningCount(value)} 个相关节点参与分析。`
  },
  kgSync: {
    title: '同步图谱状态',
    stage: 'graph',
    narrate: (value) => `系统记录的知识图谱同步状态为“${formatTraceValue(value)}”，用于判断上下文是否可直接使用。`
  },
  nodes: {
    title: '命中图谱节点',
    stage: 'graph',
    narrate: (value) => `在语义补全过程中，系统最终命中了 ${formatReasoningCount(value)} 个核心图谱节点。`
  },
  labels: {
    title: '识别业务标签',
    stage: 'understanding',
    narrate: (value) => {
      const tokens = splitReasoningTokens(value)
      return tokens.length
        ? `系统识别到的业务标签包括：${tokens.join('、')}。`
        : '系统尝试识别业务标签，但当前日志未保留具体标签内容。'
    }
  },
  hintKeys: {
    title: '参考映射提示',
    stage: 'sql',
    narrate: (value) => {
      const tokens = splitReasoningTokens(value)
      return tokens.length
        ? `字段映射主要参考了这些提示来源：${tokens.join('、')}。`
        : '系统参考了字段映射提示，但当前日志没有保留具体来源。'
    }
  },
  nl2sqlEngine: {
    title: '生成 SQL',
    stage: 'sql',
    narrate: (value) => `系统使用${describeReasoningEngine(value)}来生成本次可执行 SQL。`
  },
  reason: {
    title: '调整生成策略',
    stage: 'sql',
    narrate: (value) => `由于${String(value || '未记录原因').trim()}，系统对本次 SQL 生成策略做了相应调整。`
  },
  queryGuard: {
    title: '执行前校验',
    stage: 'guard',
    narrate: (value) => {
      const text = String(value || '').trim().toUpperCase()
      if (text === 'ALLOW') {
        return '在执行前保护环节，系统判定该查询满足当前的安全与性能要求，可以继续执行。'
      }
      if (text === 'BLOCKED') {
        return '在执行前保护环节，系统判定该查询存在风险，因此在真正执行前进行了拦截。'
      }
      if (text === 'WARN') {
        return '在执行前保护环节，系统识别到潜在风险，但仍保留了继续执行的记录。'
      }
      return `系统记录的执行前保护动作是“${formatTraceValue(value)}”。`
    }
  },
  timeoutMs: {
    title: '设置超时保护',
    stage: 'guard',
    narrate: (value) => `系统为本次查询设置了 ${formatReasoningCount(value)} ms 的执行超时保护。`
  },
  maxRows: {
    title: '限制返回结果',
    stage: 'guard',
    narrate: (value) => `系统将最终结果的最大返回行数控制在 ${formatReasoningCount(value)} 行以内，避免一次返回过多数据。`
  },
  maxScanRows: {
    title: '限制扫描范围',
    stage: 'guard',
    narrate: (value) => `系统将最大扫描行数控制在 ${formatReasoningCount(value)} 行以内，避免大范围扫描影响性能。`
  },
  limitInjected: {
    title: '补充结果限制',
    stage: 'guard',
    narrate: (value) => isTruthyReasoningValue(value)
      ? '系统已自动为 SQL 补充结果行数限制，防止一次返回过大的结果集。'
      : '本次 SQL 没有额外补充结果行数限制。'
  },
  explainRows: {
    title: '预估执行成本',
    stage: 'guard',
    narrate: (value) => `执行前评估显示，本次查询预计需要扫描约 ${formatReasoningCount(value)} 行数据。`
  },
  explainBlocked: {
    title: '检查执行风险',
    stage: 'guard',
    narrate: (value) => isTruthyReasoningValue(value)
      ? '由于执行前评估的扫描规模过大，系统在真正执行前就进行了阻断。'
      : '执行前评估没有触发阻断条件，因此查询可以继续。'
  },
  selectStar: {
    title: '检查查询写法',
    stage: 'guard',
    narrate: (value) => isTruthyReasoningValue(value)
      ? '系统检测到 SQL 使用了全字段查询写法，因此记录了潜在的性能与权限风险。'
      : '系统没有检测到 SELECT * 这类高风险查询写法。'
  },
  executeStatus: {
    title: '返回执行结果',
    stage: 'result',
    narrate: (value) => {
      const text = String(value || '').trim().toUpperCase()
      if (text === 'SUCCESS') return 'SQL 执行成功，系统进入结果整理和图表组织阶段。'
      if (text === 'FAILED') return 'SQL 执行失败，因此本次分析未能完整返回结果。'
      if (text === 'BLOCKED') return 'SQL 在执行阶段被拦截，没有继续返回结果。'
      return `系统记录的执行状态为“${formatTraceValue(value)}”。`
    }
  },
  durationMs: {
    title: '记录执行耗时',
    stage: 'result',
    narrate: (value) => `最终这次查询的执行耗时为 ${formatReasoningCount(value)} ms。`
  },
  error: {
    title: '记录异常信息',
    stage: 'result',
    narrate: (value) => `执行过程中返回了异常信息：${String(value || '未记录具体错误').trim()}。`
  }
}

const splitReasoningText = (text) => String(text || '')
  .split(/\n|；|;/)
  .map(item => item.trim())
  .filter(Boolean)

const inferReasoningStage = (step) => {
  const text = `${step?.title || ''} ${step?.detail || ''}`
  if (text.includes('意图') || text.includes('理解') || text.includes('需求') || text.toLowerCase().includes('intent')) {
    return reasoningStageCatalog.understanding
  }
  if (text.includes('图谱') || text.includes('字段') || text.includes('表') || text.includes('schema') || text.includes('匹配')) {
    return reasoningStageCatalog.dataset
  }
  if (text.includes('sql') || text.includes('查询') || text.includes('语句')) {
    return reasoningStageCatalog.sql
  }
  if (text.includes('图表') || text.includes('可视化') || text.includes('chart')) {
    return reasoningStageCatalog.result
  }
  if (text.includes('权限') || text.includes('审计') || text.includes('缓存') || text.includes('执行') || text.includes('保护')) {
    return reasoningStageCatalog.guard
  }
  return reasoningStageCatalog.record
}

const isGenericReasoningTitle = (title) => /^步骤\s*\d+$/i.test(String(title || '').trim())

const narrateReasoningPart = (part, currentDetail) => {
  const [rawKey, ...rest] = String(part || '').split('=')
  const key = rawKey?.trim()
  const value = rest.join('=').trim()
  if (key && rest.length) {
    const narrator = reasoningNarratorMap[key]
    if (narrator) {
      return {
        key,
        title: narrator.title,
        stage: reasoningStageCatalog[narrator.stage] || reasoningStageCatalog.record,
        sentence: narrator.narrate(value, currentDetail)
      }
    }
    return {
      key,
      title: traceLabelMap[key] || '过程记录',
      stage: reasoningStageCatalog.record,
      sentence: `系统记录了${traceLabelMap[key] || key}，当前值为“${formatTraceValue(value)}”。`
    }
  }
  return {
    key: '',
    title: '',
    stage: inferReasoningStage({ detail: part }),
    sentence: String(part || '').trim()
  }
}

const buildReasoningDisplaySteps = (currentDetail) => {
  const steps = Array.isArray(currentDetail?.reasoningReplaySteps) ? currentDetail.reasoningReplaySteps : []
  return steps.map((step, index) => {
    const narratives = splitReasoningText(step?.detail)
      .map(part => narrateReasoningPart(part, currentDetail))
      .filter(item => item?.sentence)
    const primaryNarrative = narratives[0]
    const defaultTitle = String(step?.title || `步骤 ${index + 1}`).trim() || `步骤 ${index + 1}`
    const resolvedTitle = isGenericReasoningTitle(defaultTitle)
      ? (primaryNarrative?.title || primaryNarrative?.stage?.label || `步骤 ${index + 1}`)
      : defaultTitle
    const resolvedStage = primaryNarrative?.stage || inferReasoningStage(step)
    const sentenceList = narratives.length ? narratives.map(item => item.sentence) : splitReasoningText(step?.detail)
    const mainSentence = sentenceList[0] || '未记录该步骤的详细说明'
    return {
      ...step,
      id: `${resolvedTitle}-${index}`,
      title: resolvedTitle,
      indexLabel: String(index + 1).padStart(2, '0'),
      stage: resolvedStage,
      mainDetail: `${sequenceLead(index, steps.length)}${mainSentence}`,
      extraDetails: sentenceList.slice(1, 4)
    }
  })
}

const parseExecutionGuard = (text, prefix) => parseTraceLine(text, prefix)
  .filter(Boolean)

const normalizeAuditLog = (log, index = 'log') => {
  if (!log || typeof log !== 'object') return {}
  const matchedRuleItems = splitCsvList(log.matchedRules).map(mapAuditRuleLabel)
  const sensitiveFieldItems = splitCsvList(log.sensitiveFields)
  return {
    ...log,
    matchedRuleItems,
    sensitiveFieldItems,
    queryGuardActionLabel: formatTraceValue(log.queryGuardAction),
    executionGuardItems: parseExecutionGuard(log.executionGuard, `guard-${log.id || index}`),
    generationTraceItems: parseTraceText(log.generationTrace, `gen-${log.id || index}`)
  }
}

const normalizeConversationContext = (payload, fallbackDetail = detail.value) => {
  const context = payload && typeof payload === 'object' ? payload : {}
  const turns = Array.isArray(context.turns)
    ? context.turns.map((turn, index) => ({
      ...turn,
      roleLabel: turn.roleLabel || conversationRoleLabel(turn.role),
      messageText: String(turn.messageText || '').trim(),
      artifacts: Array.isArray(turn.artifacts)
        ? turn.artifacts.map(artifact => ({
          ...artifact,
          sqlText: String(
            artifact?.sqlText ||
            artifact?.generatedSql ||
            artifact?.sql ||
            artifact?.artifact?.sqlText ||
            artifact?.artifact?.generatedSql ||
            artifact?.artifact?.sql ||
            ''
          ).trim(),
          summary: String(artifact?.summary || '').trim(),
          message: String(artifact?.message || '').trim(),
          question: String(artifact?.question || '').trim()
        }))
        : [],
      isCurrent: Boolean(turn.isCurrent),
      isCurrentPrompt: Boolean(turn.isCurrentPrompt),
      _index: index
    }))
    : []
  if (turns.length) {
    return {
      ...context,
      conversation: context.conversation || {},
      turns,
      totalTurns: Number(context.totalTurns || turns.length)
    }
  }
  const current = fallbackDetail || {}
  return {
    currentHistoryId: current.id,
    currentTurnNo: current.turnNo,
    fallbackMode: true,
    fallbackReason: context.fallbackReason || '该历史记录暂无可关联的会话上下文',
    conversation: context.conversation || {},
    turns: [{
      role: 'ASSISTANT',
      roleLabel: '助手',
      turnNo: current.turnNo,
      messageText: current.summaryText || current.riskReason || '该历史记录仅保留了单条查询结果。',
      createdAt: current.createdAt,
      isCurrent: true,
      artifacts: [{
        historyId: current.id,
        artifactType: current.artifactType || 'CHART',
        artifactTypeLabel: current.artifactType || '图表',
        summary: current.question || current.summaryText || '历史查询结果',
        sqlText: current.generatedSql || current.sql || '',
        chartType: current.chartType,
        riskLevel: current.riskLevel
      }]
    }],
    totalTurns: 1,
    contextError: context.contextError || ''
  }
}

const formatGuardSummary = (log) => {
  const action = log.queryGuardActionLabel || '未触发执行保护'
  if (!log.executionGuardItems?.length) return action
  const highlights = []
  const explainBlocked = log.executionGuardItems.find(item => item.key === 'explainBlocked' && item.value === '是')
  const selectStar = log.executionGuardItems.find(item => item.key === 'selectStar' && item.value === '是')
  if (explainBlocked) highlights.push('Explain 预估扫描量超阈值')
  if (selectStar) highlights.push('检测到 SELECT *')
  if (!highlights.length) highlights.push('已按执行前规则完成校验')
  return `${action}：${highlights.join('；')}`
}

const asTrendPoints = (rows, valueKey) => (Array.isArray(rows) ? rows : []).map((item) => ({
  name: item.day || '-',
  value: Number(item?.[valueKey] || 0)
}))

const normalizeDetail = (payload) => {
  if (!payload) return payload
  const auditLogs = Array.isArray(payload.auditLogs)
    ? payload.auditLogs.map((log, index) => normalizeAuditLog(log, index))
    : []
  const latestAuditLog = normalizeAuditLog(payload.latestAuditLog, 'latest')
  const cacheContext = payload.cacheContext && typeof payload.cacheContext === 'object'
    ? {
      ...payload.cacheContext,
      cacheHitLabel: payload.cacheContext.cacheHit ? '命中缓存' : '未命中缓存',
      redisStatusText: payload.cacheContext.redisStatus
        ? formatTraceValue(payload.cacheContext.redisStatus)
        : payload.cacheContext.cacheHit
          ? '已命中缓存，但没有记录 Redis 侧状态。'
          : '未命中缓存，或当前日志未返回 Redis 侧状态。',
      queryGuardActionLabel: formatTraceValue(payload.cacheContext.queryGuardAction)
    }
    : {}
  return {
    ...payload,
    auditLogs,
    latestAuditLog,
    cacheContext,
    permissionCheck: payload.permissionCheck || {}
  }
}

const buildParams = () => ({
  page: page.value,
  pageSize: pageSize.value,
  keyword: filters.keyword || undefined,
  userId: filters.userId || undefined,
  tableName: filters.tableName || undefined,
  sourceType: filters.sourceType || undefined,
  chartType: filters.chartType || undefined,
  riskLevel: filters.riskLevel || undefined,
  executionStatus: filters.executionStatus || undefined,
  modelType: filters.modelType || undefined,
  cacheHit: filters.cacheHit,
  slowQuery: filters.slowQuery,
  dateFrom: dateRange.value?.[0] || undefined,
  dateTo: dateRange.value?.[1] || undefined,
  sortDirection: filters.sortDirection
})

const buildAnalyticsParams = () => ({
  keyword: filters.keyword || undefined,
  userId: filters.userId || undefined,
  tableName: filters.tableName || undefined,
  sourceType: filters.sourceType || undefined,
  chartType: filters.chartType || undefined,
  riskLevel: filters.riskLevel || undefined,
  executionStatus: filters.executionStatus || undefined,
  modelType: filters.modelType || undefined,
  cacheHit: filters.cacheHit,
  slowQuery: filters.slowQuery,
  dateFrom: dateRange.value?.[0] || undefined,
  dateTo: dateRange.value?.[1] || undefined
})

const loadList = async (targetPage = page.value) => {
  loading.value = true
  page.value = targetPage
  try {
    const [data, analyticsData] = await Promise.all([
      fetchAdminChatHistory(buildParams()),
      fetchAdminChatHistoryAnalytics(buildAnalyticsParams())
    ])
    rows.value = Array.isArray(data.items) ? data.items : []
    total.value = Number(data.total || 0)
    summary.value = data.summary || {}
    governance.value = data.governance || {}
    analytics.value = analyticsData || { trends: {}, performance: {}, routeAudit: {} }
  } catch (error) {
    ElMessage.error(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const openDetail = async (row) => {
  if (!row?.id) return
  detailVisible.value = true
  detailActiveTab.value = 'query'
  detail.value = null
  try {
    const [detailPayload, contextPayload] = await Promise.all([
      fetchAdminChatHistoryDetail(row.id),
      fetchAdminChatHistoryContext(row.id).catch(error => ({
        fallbackMode: true,
        contextError: error.message || '对话上下文加载失败'
      }))
    ])
    const normalizedDetail = normalizeDetail(detailPayload)
    normalizedDetail.conversationContext = normalizeConversationContext(contextPayload, normalizedDetail)
    detail.value = normalizedDetail
  } catch (error) {
    detailVisible.value = false
    ElMessage.error(error.message || '详情加载失败')
  }
}

const rerun = async (row) => {
  if (!row?.id) return
  rerunning.value = true
  try {
    const result = await rerunAdminChatHistory(row.id)
    const newHistoryText = result.newHistoryId ? `，新历史 #${result.newHistoryId}` : ''
    ElMessage.success(`${result.message || '复跑完成'}${newHistoryText}`)
    await loadList(page.value)
    if (detailVisible.value && detail.value?.id === row.id) {
      const [detailPayload, contextPayload] = await Promise.all([
        fetchAdminChatHistoryDetail(row.id),
        fetchAdminChatHistoryContext(row.id).catch(error => ({
          fallbackMode: true,
          contextError: error.message || '对话上下文加载失败'
        }))
      ])
      const normalizedDetail = normalizeDetail(detailPayload)
      normalizedDetail.conversationContext = normalizeConversationContext(contextPayload, normalizedDetail)
      detail.value = normalizedDetail
    }
  } catch (error) {
    ElMessage.error(error.message || '复跑失败')
  } finally {
    rerunning.value = false
  }
}

const deleteOne = async (row) => {
  if (!row?.id) return
  try {
    await ElMessageBox.confirm(`确认删除这条历史记录？`, '删除确认', {
      type: 'warning'
    })
    const result = await deleteAdminChatHistoryBatch([row.id], '管理员手动删除单条历史记录')
    ElMessage.success(`已删除，保留 ${result.retentionDays || governance.value.deletedRetentionDays || 30} 天治理留痕`)
    if (detailVisible.value && detail.value?.id === row.id) {
      detailVisible.value = false
      detail.value = null
    }
    await loadList(page.value)
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(error.message || '删除失败')
  }
}

const deleteSelected = async () => {
  if (!selectedIds.value.length) return
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedIds.value.length} 条记录？`, '批量删除', {
      type: 'warning'
    })
    const result = await deleteAdminChatHistoryBatch(selectedIds.value, '管理员批量删除历史记录')
    selectedIds.value = []
    ElMessage.success(`批量删除完成，已记录 ${result.auditCount || selectedIds.value.length} 条治理审计`)
    await loadList(page.value)
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(error.message || '批量删除失败')
  }
}

const exportExcel = async () => {
  exporting.value = true
  try {
    const response = await http.get('/api/admin/chat-history/export', {
      params: {
        ...buildParams(),
        limit: Math.max(total.value, pageSize.value)
      },
      responseType: 'blob'
    })
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'admin-chat-history.xlsx'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出完成')
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

const copySql = async (sql) => {
  if (!sql) {
    ElMessage.warning('没有可复制的 SQL')
    return
  }
  try {
    await navigator.clipboard.writeText(sql)
    ElMessage.success('SQL 已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const resetFilters = () => {
  filters.keyword = ''
  filters.userId = ''
  filters.tableName = ''
  filters.sourceType = ''
  filters.chartType = ''
  filters.riskLevel = ''
  filters.executionStatus = ''
  filters.modelType = ''
  filters.cacheHit = null
  filters.slowQuery = null
  filters.sortDirection = 'DESC'
  dateRange.value = []
  loadList(1)
}

const toggleSort = () => {
  filters.sortDirection = filters.sortDirection === 'DESC' ? 'ASC' : 'DESC'
  loadList(1)
}

const handleSelectionChange = (selection) => {
  selectedIds.value = selection.map(item => item.id).filter(Boolean)
}

const handlePageSizeChange = (size) => {
  pageSize.value = size
  loadList(1)
}

const riskTagType = (level) => {
  if (level === 'BLOCKED') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'success'
}

const statusTagType = (status) => {
  if (status === 0) return 'danger'
  if (status === 2) return 'warning'
  return 'success'
}

const statusTextType = (status) => {
  if (status === 'FAILED' || status === 'BLOCKED') return 'danger'
  if (status === 'WARN' || status === 'CANCELLED') return 'warning'
  return 'success'
}

const sqlStatusTagType = (status) => {
  if (status === 'BLOCKED' || status === 'FAILED') return 'danger'
  if (status === 'CANCELLED' || status === 'EMPTY') return 'warning'
  return 'success'
}

const aiParseTagType = (status) => {
  if (status === 'PARSE_FAILED') return 'danger'
  if (status === 'UNKNOWN') return 'warning'
  if (status === 'RULE_FALLBACK' || status === 'CACHE_REUSED') return 'info'
  return 'success'
}

const permissionStatusTagType = (status) => {
  if (status === 'BLOCKED') return 'danger'
  if (status === 'UNKNOWN') return 'warning'
  return 'success'
}

const formatPercent = (value) => `${Number(value || 0).toFixed(1)}%`

onMounted(() => {
  loadList(1)
})
</script>

<style scoped>
.admin-chat-history {
  --panel-border: #dfe7f3;
  --panel-shadow: 0 8px 20px rgba(33, 75, 132, 0.07);
  display: grid;
  gap: 12px;
  padding: 4px;
  color: #13213c;
  background: #f5f7fb;
  min-width: 0;
}

.history-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 2px 0;
}

.history-title-block {
  min-width: 0;
}

.history-hero h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  color: #101b36;
}

.history-hero p {
  margin: 8px 0 0;
  color: #59667f;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card,
.history-panel {
  min-width: 0;
  background: #fff;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  box-shadow: var(--panel-shadow);
}

.metric-card {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 70px;
  padding: 11px 12px;
}

.metric-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  min-width: 32px;
  height: 32px;
  border-radius: 8px;
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}

.metric-content {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.metric-content span {
  color: #5b6b85;
  font-size: 12px;
  font-weight: 700;
}

.metric-content strong {
  color: #0f172a;
  font-size: 24px;
  line-height: 1.05;
}

.metric-content small {
  color: #74829a;
  font-size: 11px;
  line-height: 1.35;
  word-break: break-word;
}

.metric-card--blue .metric-icon {
  background: #2563eb;
}

.metric-card--orange .metric-icon {
  background: #f97316;
}

.metric-card--purple .metric-icon {
  background: #7c3aed;
}

.metric-card--red .metric-icon {
  background: #ef4444;
}

.metric-card--blue {
  background: #ffffff;
}

.metric-card--orange {
  background: #ffffff;
}

.metric-card--purple {
  background: #ffffff;
}

.metric-card--red {
  background: #ffffff;
}

.history-panel {
  padding: 12px;
}

.history-dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 12px;
  align-items: start;
}

.history-workspace,
.history-side-rail {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.history-side-rail {
  align-content: start;
}

.filter-panel,
.table-panel,
.trend-panel,
.side-panel {
  min-width: 0;
}

.analysis-grid,
.performance-summary-grid,
.performance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.analysis-card {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 10px;
  border: 1px solid #e7eef8;
  border-radius: 8px;
  background: #ffffff;
}

.analysis-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.analysis-card-head h3 {
  margin: 0;
  color: #13213c;
  font-size: 13px;
}

.analysis-card-head p {
  margin: 4px 0 0;
  color: #74829a;
  font-size: 11px;
  line-height: 1.45;
}

.hot-list {
  display: grid;
  gap: 8px;
}

.hot-list--side {
  max-height: 244px;
  overflow: auto;
  padding-right: 2px;
}

.hot-list-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 9px;
  border-radius: 8px;
  background: #f8fbff;
  border: 1px solid #eef3fb;
}

.hot-list-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.hot-list-main strong {
  color: #13213c;
  font-size: 11px;
  line-height: 1.45;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hot-list-main small {
  color: #74829a;
  font-size: 10px;
  word-break: break-word;
}

.hot-list-meta {
  display: grid;
  gap: 3px;
  text-align: right;
  flex-shrink: 0;
}

.hot-list-meta span {
  color: #74829a;
  font-size: 10px;
}

.hot-list-meta strong {
  color: #13213c;
  font-size: 11px;
}

.route-audit-panel {
  border-color: #dce8f7;
  /* background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.92), rgba(255, 255, 255, 0.98)),
    #ffffff; */
}

.route-audit-stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.route-audit-stat {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 8px;
  border: 1px solid #e5edf8;
  border-radius: 8px;
  background: #ffffff;
}

.route-audit-stat span,
.route-audit-section-head span {
  color: #66758e;
  font-size: 10px;
  font-weight: 700;
}

.route-audit-stat strong {
  color: #13213c;
  font-size: 16px;
  line-height: 1.15;
}

.route-audit-stat small,
.route-audit-section-head small {
  color: #8a98ad;
  font-size: 10px;
  line-height: 1.35;
}

.route-audit-stat--blue {
  border-color: #dbeafe;
  background: #f8fbff;
}

.route-audit-stat--green {
  border-color: #d9f0e7;
  background: #f7fffb;
}

.route-audit-stat--amber {
  border-color: #fde6bf;
  background: #fffaf1;
}

.route-audit-stat--red {
  border-color: #ffd8d8;
  background: #fff7f7;
}

.route-audit-section {
  display: grid;
  gap: 7px;
  margin-top: 10px;
}

.route-audit-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.route-audit-list {
  display: grid;
  gap: 7px;
}

.route-audit-list--samples {
  max-height: 280px;
  overflow: auto;
  padding-right: 2px;
}

.route-audit-row,
.route-audit-sample {
  min-width: 0;
  padding: 8px 9px;
  border: 1px solid #edf2fa;
  border-radius: 8px;
  background: #ffffff;
}

.route-audit-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.route-audit-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.route-audit-main strong,
.route-audit-sample strong {
  color: #13213c;
  font-size: 11px;
  line-height: 1.45;
  word-break: break-word;
}

.route-audit-main small,
.route-audit-sample small {
  color: #74829a;
  font-size: 10px;
  line-height: 1.45;
  word-break: break-word;
}

.route-audit-meta {
  display: grid;
  gap: 3px;
  text-align: right;
  flex-shrink: 0;
}

.route-audit-meta span {
  color: #16a34a;
  font-size: 10px;
  font-weight: 700;
}

.route-audit-meta strong {
  color: #d97706;
  font-size: 11px;
}

.route-audit-sample {
  display: grid;
  gap: 5px;
}

.route-audit-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.panel-head--compact {
  margin-bottom: 8px;
}

.panel-head--table {
  align-items: center;
}

.panel-head h2 {
  margin: 0;
  font-size: 14px;
  color: #13213c;
}

.panel-head p {
  margin: 4px 0 0;
  color: #66758e;
  font-size: 11px;
  line-height: 1.45;
}

.panel-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(96px, 1fr));
  gap: 8px;
}

.filter-field {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.filter-field span {
  color: #475569;
  font-size: 11px;
  font-weight: 700;
}

.filter-field--wide {
  grid-column: span 2;
}

.filter-field--date {
  grid-column: span 2;
}

.filter-actions {
  display: flex;
  gap: 6px;
  align-items: end;
}

.filter-actions :deep(.el-button) {
  flex: 1;
}

.side-stat-list {
  display: grid;
  gap: 7px;
}

.side-stat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 9px;
  border-radius: 8px;
  background: #f8fbff;
  border: 1px solid #eef3fb;
}

.side-stat span {
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
}

.side-stat strong {
  color: #13213c;
  font-size: 13px;
}

.history-table :deep(.el-table__row) {
  cursor: pointer;
}

.history-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.history-table :deep(.el-table__header-wrapper th) {
  background: #f8fbff;
  color: #42526d;
  font-weight: 700;
}

.user-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.user-cell strong {
  color: #13213c;
  font-size: 11px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-cell small {
  color: #74829a;
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.query-cell strong {
  color: #13213c;
  font-size: 11px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-cell small {
  color: #74829a;
  font-size: 10px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex-wrap: nowrap;
}

.table-actions :deep(.table-action-btn.el-button) {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 0;
  border-radius: 50%;
}

.table-actions :deep(.table-action-btn .el-icon) {
  font-size: 13px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 10px;
  color: #74829a;
  font-size: 11px;
}

.detail-wrap {
  display: grid;
  gap: 14px;
}

.detail-wrap--drawer {
  zoom: 1;
  width: 100%;
  transform: none;
}

.detail-layout {
  display: grid;
  grid-template-columns: minmax(320px, 390px) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  overflow: hidden;
}

.detail-sidebar,
.detail-content {
  display: grid;
  gap: 12px;
  min-width: 0;
  align-content: start;
}

.detail-sidebar {
  align-content: start;
  position: sticky;
  top: 10px;
  overflow: visible;
  max-width: 390px;
}

.detail-overview {
  display: grid;
  gap: 12px;
}

.detail-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid #dfe7f3;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(44, 74, 124, 0.08);
}

.detail-panel--muted {
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-overview-shell {
  gap: 14px;
  border-color: #dbeafe;
  box-shadow: 0 10px 26px rgba(44, 74, 124, 0.08);
}

.detail-overview-main {
  display: grid;
  align-content: start;
  gap: 10px;
}

.detail-badges {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
  overflow: visible;
}

.detail-badges :deep(.el-tag) {
  min-width: 0;
  height: 28px;
  justify-content: center;
  border-radius: 7px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-question-card {
  position: relative;
  display: grid;
  gap: 7px;
  padding: 12px 13px 12px 15px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
  overflow: hidden;
}

.detail-question-card::before {
  content: "";
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: #2563eb;
}

.detail-question-card > span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.detail-overview-main h2 {
  margin: 0;
  color: #0f172a;
  font-size: 18px;
  line-height: 1.35;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail-overview-main p {
  margin: 0;
  color: #52637f;
  line-height: 1.55;
  font-size: 13px;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail-overview-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.detail-overview-grid--compact {
  gap: 10px;
}

.detail-overview-group {
  display: grid;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e8eef8;
}

.detail-overview-group-title {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #334155;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.4;
}

.detail-overview-group-title::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.1);
}

.detail-overview-list {
  display: grid;
  gap: 8px;
}

.detail-overview-row {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 9px 10px;
  border: 1px solid #eef4fb;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
}

.detail-overview-row > span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.detail-overview-value {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.detail-overview-value strong {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.45;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail-overview-value small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail-section {
  display: grid;
  gap: 10px;
}

.detail-tabs {
  min-width: 0;
}

.detail-tabs :deep(.el-tabs__header) {
  margin: 0 0 10px;
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  background: #e8eef8;
}

.detail-tabs :deep(.el-tabs__item) {
  height: 32px;
  line-height: 32px;
  font-size: 12px;
}

.detail-tab-stack {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.detail-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.detail-section-title {
  display: grid;
  gap: 4px;
}

.detail-section h3 {
  margin: 0;
  color: #13213c;
  font-size: 15px;
}

.detail-section-title p {
  margin: 0;
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.detail-query-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: 10px;
}

.detail-query-grid--single {
  grid-template-columns: 1fr;
}

.detail-subpanel {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.detail-subpanel--code {
  background: #0f172a;
  border-color: #1e293b;
}

.detail-subpanel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.detail-subpanel-head span {
  color: #5b6c86;
  font-size: 12px;
  font-weight: 700;
}

.detail-subpanel--code .detail-subpanel-head span {
  color: #cbd5e1;
}

.detail-query-section {
  gap: 14px;
  border-color: #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-query-head {
  align-items: center;
}

.detail-query-actions :deep(.el-button) {
  height: 36px;
  border-radius: 8px;
  font-weight: 700;
}

.detail-query-section .detail-query-grid {
  grid-template-columns: 1fr;
  gap: 12px;
  align-items: stretch;
}

.detail-query-section .detail-query-grid--single {
  grid-template-columns: 1fr;
}

.detail-query-section .detail-subpanel {
  min-width: 0;
  gap: 0;
  padding: 0;
  overflow: hidden;
  border-color: #dbeafe;
  background: #ffffff;
  box-shadow: 0 10px 22px rgba(44, 74, 124, 0.06);
}

.detail-query-section .detail-subpanel-head {
  align-items: flex-start;
  padding: 12px 14px 10px;
  border-bottom: 1px solid #e8eef8;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-subpanel-title {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.detail-subpanel-title span {
  color: #1e3a8a;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.detail-subpanel-title small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.detail-subpanel--question {
  position: relative;
  min-height: 0;
}

.detail-subpanel--question::before {
  content: "";
  position: absolute;
  inset: 54px auto 14px 0;
  width: 3px;
  border-radius: 999px;
  background: #2563eb;
}

.detail-text--question {
  min-height: 0;
  padding: 16px 18px 14px;
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.75;
}

.detail-question-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-top: 1px solid #e8eef8;
  background: #fbfdff;
}

.detail-question-meta span {
  max-width: 100%;
  padding: 3px 8px;
  border: 1px solid #e8eef8;
  border-radius: 999px;
  background: #ffffff;
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
  word-break: break-word;
}

.detail-query-section .detail-subpanel--code {
  min-height: 0;
  border-color: #bfdbfe;
  background: #0f172a;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.14);
}

.detail-query-section .detail-subpanel--code .detail-subpanel-head {
  border-bottom-color: rgba(147, 197, 253, 0.18);
  background: linear-gradient(180deg, #13213c 0%, #0f172a 100%);
}

.detail-query-section .detail-subpanel--code .detail-subpanel-title span {
  color: #dbeafe;
}

.detail-query-section .detail-subpanel--code .detail-subpanel-title small {
  color: #93c5fd;
}

.detail-query-section .detail-subpanel--code :deep(.el-tag) {
  max-width: 180px;
  border-color: rgba(147, 197, 253, 0.35);
  background: rgba(37, 99, 235, 0.14);
  color: #bfdbfe;
  font-weight: 700;
}

.detail-code-shell {
  min-height: 172px;
  display: flex;
  background:
    radial-gradient(circle at top right, rgba(37, 99, 235, 0.18), transparent 42%),
    #0f172a;
}

.detail-code-shell .detail-code {
  flex: 1;
  max-height: 260px;
  padding: 16px;
  color: #dbeafe;
  font-size: 12.5px;
  line-height: 1.75;
  scrollbar-width: thin;
  scrollbar-color: #3b82f6 rgba(15, 23, 42, 0.3);
}

.detail-code-shell .detail-code::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.detail-code-shell .detail-code::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #3b82f6;
}

.detail-code-shell .detail-code::-webkit-scrollbar-track {
  background: rgba(15, 23, 42, 0.3);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.audit-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.audit-summary-item {
  position: relative;
  min-width: 0;
  display: grid;
  gap: 7px;
  align-content: start;
  min-height: 112px;
  padding: 11px 12px 11px 14px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
  box-sizing: border-box;
  overflow: hidden;
}

.audit-summary-item::before {
  content: "";
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: #93c5fd;
}

.audit-summary-head {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.audit-summary-head span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.audit-summary-head em {
  max-width: 84px;
  padding: 2px 7px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
  line-height: 1.35;
  text-align: center;
  white-space: nowrap;
}

.audit-summary-item strong {
  color: #13213c;
  font-size: 18px;
  line-height: 1.35;
  word-break: break-word;
}

.audit-summary-item p {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}

.audit-summary-item--red::before {
  background: #ef4444;
}

.audit-summary-item--red .audit-summary-head em {
  background: #fef2f2;
  color: #dc2626;
}

.audit-summary-item--amber::before {
  background: #f59e0b;
}

.audit-summary-item--amber .audit-summary-head em {
  background: #fffbeb;
  color: #b45309;
}

.audit-summary-item--green::before {
  background: #10b981;
}

.audit-summary-item--green .audit-summary-head em {
  background: #ecfdf5;
  color: #047857;
}

.audit-summary-item--neutral::before {
  background: #cbd5e1;
}

.audit-summary-item--neutral .audit-summary-head em {
  background: #f1f5f9;
  color: #64748b;
}

.context-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.context-card {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.context-card--wide {
  grid-column: 1 / -1;
}

.context-card--code {
  background: #ffffff;
}

.context-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.context-card-head span {
  color: #5b6c86;
  font-size: 12px;
  font-weight: 700;
}

.context-card strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.context-card p {
  margin: 0;
  color: #52637f;
  font-size: 12px;
  line-height: 1.7;
}

.detail-summary-section {
  gap: 14px;
  border-color: #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-summary-head {
  padding-bottom: 2px;
}

.detail-summary-grid {
  gap: 12px;
}

.summary-card {
  position: relative;
  gap: 12px;
  min-width: 0;
  min-height: 132px;
  padding: 14px;
  overflow: hidden;
  border-color: #dbeafe;
  background: #ffffff;
  box-shadow: 0 10px 22px rgba(44, 74, 124, 0.06);
}

.summary-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: #2563eb;
}

.summary-card::after {
  content: "";
  position: absolute;
  right: -28px;
  top: -34px;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: rgba(37, 99, 235, 0.08);
}

.summary-card--primary {
  min-height: 128px;
  padding: 16px 16px 16px 18px;
  background:
    linear-gradient(135deg, rgba(239, 246, 255, 0.95) 0%, #ffffff 58%),
    #ffffff;
}

.summary-card--parse::before {
  background: #0ea5e9;
}

.summary-card--parse::after {
  background: rgba(14, 165, 233, 0.08);
}

.summary-card--sql::before {
  background: #22c55e;
}

.summary-card--sql::after {
  background: rgba(34, 197, 94, 0.08);
}

.summary-card--alert::before {
  background: #f97316;
}

.summary-card--alert::after {
  background: rgba(249, 115, 22, 0.1);
}

.summary-card-head {
  position: relative;
  z-index: 1;
  align-items: flex-start;
}

.summary-card-head :deep(.el-tag) {
  max-width: 220px;
  border-radius: 7px;
  font-weight: 700;
}

.summary-card-title {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.summary-card-title span {
  color: #1e3a8a;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.summary-card-title small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.summary-card-body {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 8px;
  min-width: 0;
}

.summary-card-body strong {
  color: #0f172a;
  font-size: 15px;
  line-height: 1.55;
  word-break: break-word;
}

.summary-card--primary .summary-card-body strong {
  font-size: 17px;
  line-height: 1.5;
}

.summary-card-body p {
  margin: 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}

.alert-summary-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.alert-summary-item {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fffaf3;
}

.alert-summary-item--wide {
  grid-column: 1 / -1;
}

.alert-summary-item span {
  color: #92400e;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.alert-summary-item strong {
  color: #1f2937;
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.context-meta {
  display: grid;
  gap: 4px;
  padding-top: 8px;
  border-top: 1px dashed #d7e3f1;
}

.context-meta span {
  color: #74829a;
  font-size: 12px;
}

.context-meta strong {
  font-size: 13px;
}

.audit-context-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.audit-context-summary-item {
  position: relative;
  min-height: 110px;
  border-color: #dbeafe;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
  overflow: hidden;
}

.audit-context-summary-item::before {
  content: "";
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  border-radius: 0 999px 999px 0;
  background: #2563eb;
}

.audit-context-summary-item span,
.audit-context-summary-item p {
  color: #64748b;
}

.audit-context-summary-item strong {
  color: #13213c;
}

.audit-context-grid {
  gap: 10px;
  align-items: start;
}

.audit-context-card {
  gap: 9px;
  min-height: 0;
  padding: 12px;
  border-color: #dbeafe;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
  box-shadow: none;
  overflow: hidden;
}

.audit-context-card-head {
  align-items: center;
  padding-bottom: 0;
}

.audit-context-card-head span {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.audit-context-card-head :deep(.el-tag) {
  max-width: 148px;
  min-height: 24px;
  justify-content: center;
  border-radius: 6px;
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
  font-weight: 700;
  white-space: nowrap;
}

.audit-context-card > strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.audit-context-card > p {
  color: #52637f;
  font-size: 12px;
  line-height: 1.65;
}

.audit-context-meta {
  gap: 6px;
  padding: 8px 0 0;
  border-top: 1px dashed #dbeafe;
  border-radius: 0;
  background: transparent;
}

.audit-context-meta + .audit-context-meta {
  margin-top: 0;
}

.audit-context-meta span {
  color: #64748b;
  font-weight: 500;
  line-height: 1.4;
}

.audit-context-meta strong {
  color: #13213c;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.audit-context-card--mask,
.audit-context-card--sql {
  min-height: 0;
}

.audit-sql-preview {
  max-width: 100%;
  overflow: hidden;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}

.audit-sql-preview .detail-code--light {
  max-height: 148px;
  border: 0;
  border-radius: 0;
  background: #ffffff;
  color: #1e3a8a;
  white-space: pre;
  overflow: auto;
}

.detail-text {
  margin: 0;
  color: #334155;
  line-height: 1.7;
  white-space: pre-wrap;
  font-size: 13px;
}

.detail-code {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: transparent;
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.detail-code--small {
  max-height: 140px;
}

.detail-code--light {
  max-height: 180px;
  padding: 12px;
  border: 1px solid #e5edf7;
  background: #0f172a;
  color: #dbeafe;
}

.detail-code--light::selection {
  background: rgba(59, 130, 246, 0.35);
}

.detail-code--light::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.detail-code--light::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.35);
}

.detail-code--light::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.04);
}

.audit-log-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.audit-log-list {
  display: grid;
  gap: 12px;
}

.audit-log-summary {
  display: grid;
  gap: 8px;
}

.audit-log-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 2px;
}

.audit-log-title {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.audit-log-title strong {
  color: #0f172a;
  font-size: 15px;
  line-height: 1.45;
  word-break: break-word;
}

.audit-log-title span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.audit-log-tags {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  flex-wrap: wrap;
}

.audit-log-tags :deep(.el-tag) {
  min-height: 26px;
  border-radius: 6px;
  font-weight: 700;
}

.audit-log-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: start;
  gap: 8px;
}

.audit-log-metric {
  display: grid;
  gap: 6px;
  min-height: 74px;
  padding: 10px 11px;
  border-radius: 8px;
  background: #f8fbff;
  border: 1px solid #e8eef8;
}

.audit-log-metric--wide {
  grid-column: 1 / -1;
  min-height: 0;
}

.audit-log-grid span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.audit-log-grid strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.reasoning-graph-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
  gap: 12px;
  align-items: start;
}

.reasoning-graph-layout > .detail-section {
  align-self: start;
  align-content: start;
}

.reasoning-mini-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  align-items: start;
  align-content: start;
}

.reasoning-mini-item {
  display: grid;
  gap: 6px;
  padding: 11px 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
  min-height: 84px;
  align-content: start;
  box-sizing: border-box;
}

.reasoning-mini-item span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.reasoning-mini-item strong {
  color: #13213c;
  font-size: 15px;
  line-height: 1.5;
  word-break: break-word;
}

.reasoning-mini-item p {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}

.reasoning-timeline-scroll {
  max-height: min(107vh, 736px);
  min-height: 220px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 2px 8px 2px 0;
  scrollbar-width: thin;
  scrollbar-color: #bfdbfe transparent;
}

.reasoning-timeline-scroll::-webkit-scrollbar {
  width: 8px;
}

.reasoning-timeline-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.reasoning-timeline-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #bfdbfe;
}

.reasoning-timeline-scroll::-webkit-scrollbar-thumb:hover {
  background: #93c5fd;
}

.reasoning-timeline {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
}

.reasoning-timeline-item {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}

.reasoning-timeline-marker {
  position: relative;
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding-top: 10px;
}

.reasoning-timeline-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12);
  position: relative;
  z-index: 1;
}

.reasoning-timeline-line {
  position: absolute;
  top: 20px;
  bottom: -12px;
  width: 2px;
  background: #dbeafe;
  border-radius: 999px;
}

.reasoning-timeline-item:last-child .reasoning-timeline-line {
  display: none;
}

.reasoning-timeline-card {
  min-width: 0;
  display: grid;
  gap: 8px;
  padding: 11px 12px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
}

.reasoning-timeline-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.reasoning-stepno {
  color: #2563eb;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.reasoning-timeline-head strong {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.45;
}

.reasoning-timeline-card p {
  margin: 0;
  color: #475569;
  font-size: 12px;
  line-height: 1.65;
  word-break: break-word;
}

.reasoning-detail-list {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 18px;
  color: #52637f;
  font-size: 12px;
  line-height: 1.6;
}

.graph-visual {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.graph-visual-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.graph-visual-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.graph-visual-head strong {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.4;
}

.graph-visual-head span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.graph-visual-canvas {
  width: 100%;
  height: 260px;
  display: block;
  border-radius: 8px;
  background:
    radial-gradient(circle at 50% 50%, rgba(219, 234, 254, 0.72), transparent 58%),
    #ffffff;
}

.graph-visual-edge-label {
  paint-order: stroke;
  stroke: #ffffff;
  stroke-width: 4px;
  fill: #64748b;
  font-size: 11px;
  font-weight: 700;
}

.graph-visual-node circle {
  stroke-width: 2px;
}

.graph-visual-node--table circle {
  fill: #eff6ff;
  stroke: #2563eb;
}

.graph-visual-node--field circle {
  fill: #ecfeff;
  stroke: #0891b2;
}

.graph-visual-node--metric circle {
  fill: #f0fdf4;
  stroke: #16a34a;
}

.graph-visual-node--relation circle {
  fill: #fff7ed;
  stroke: #f97316;
}

.graph-visual-node--context circle {
  fill: #f8fafc;
  stroke: #94a3b8;
}

.graph-visual-node-title {
  fill: #0f172a;
  font-size: 12px;
  font-weight: 800;
}

.graph-visual-node-type {
  fill: #64748b;
  font-size: 10px;
  font-weight: 700;
}

.graph-full-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.graph-full-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 320px);
  gap: 14px;
  align-items: stretch;
}

.graph-full-panel {
  min-width: 0;
  display: grid;
  gap: 10px;
  align-content: start;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #ffffff;
}

.graph-full-panel--canvas {
  overflow: hidden;
}

.graph-full-head {
  display: grid;
  gap: 3px;
}

.graph-full-head strong {
  color: #0f172a;
  font-size: 15px;
  line-height: 1.45;
}

.graph-full-head span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.graph-full-canvas {
  width: 100%;
  height: min(62vh, 620px);
  min-height: 420px;
  display: block;
  border-radius: 8px;
  background:
    radial-gradient(circle at 50% 50%, rgba(219, 234, 254, 0.75), transparent 62%),
    #ffffff;
}

.graph-relation-list {
  max-height: min(62vh, 620px);
  overflow: auto;
  display: grid;
  gap: 8px;
  padding-right: 4px;
}

.graph-relation-item {
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #f8fbff;
}

.graph-relation-item strong {
  color: #0f172a;
  font-size: 13px;
  line-height: 1.35;
  word-break: break-word;
}

.graph-relation-item span {
  width: fit-content;
  padding: 2px 8px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.35;
}

.graph-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.graph-card {
  display: grid;
  gap: 7px;
  padding: 10px 11px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
  min-width: 0;
}

.graph-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.graph-card strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.45;
  word-break: break-word;
}

.graph-card small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}

.graph-card p {
  margin: 0;
  color: #334155;
  font-size: 12px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.conversation-replay-shell {
  flex: 1 1 auto;
  min-height: 520px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e6ebf2;
  border-radius: 18px;
  background: #fff;
  box-shadow: inset 0 -1px 0 rgba(148, 163, 184, 0.08);
}

.conversation-replay-shell .chat-conversation-main {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0 14px 0;
  background: #fff;
}

.conversation-replay-shell .message-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 10px 2px 12px;
}

.conversation-context-notice {
  margin: 10px 2px 4px;
  padding: 8px 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
  color: #92400e;
  font-size: 12px;
  line-height: 1.5;
}

.conversation-replay-shell .message-wrapper {
  position: relative;
  display: flex;
  align-items: flex-start;
  margin-bottom: 20px;
  padding: 8px 4px;
  border-radius: 14px;
}

.conversation-replay-shell .message-wrapper.user {
  flex-direction: row-reverse;
}

.conversation-replay-shell .message-wrapper.is-current {
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.1), rgba(37, 99, 235, 0));
}

.conversation-replay-shell .message-wrapper.is-current-prompt {
  background: linear-gradient(90deg, rgba(245, 158, 11, 0.12), rgba(245, 158, 11, 0));
}

.conversation-replay-shell .message-wrapper.user.is-current-prompt {
  background: linear-gradient(270deg, rgba(245, 158, 11, 0.14), rgba(245, 158, 11, 0));
}

.conversation-replay-shell .message-wrapper.is-current::before,
.conversation-replay-shell .message-wrapper.is-current-prompt::before {
  content: "";
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 3px;
  border-radius: 999px;
  background: #2563eb;
}

.conversation-replay-shell .message-wrapper.user.is-current-prompt::before {
  right: 0;
  left: auto;
  background: #f59e0b;
}

.conversation-replay-shell .message-wrapper.is-current-prompt:not(.is-current)::before {
  background: #f59e0b;
}

.conversation-replay-shell .avatar {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 50%;
  background: transparent;
  box-shadow: none;
  overflow: visible;
  margin: 0 10px;
}

.conversation-replay-shell .avatar img {
  width: 44px;
  height: 44px;
  display: block;
  object-fit: contain;
  border-radius: 50%;
}

.conversation-replay-shell .message-wrapper.user .avatar {
  width: 64px;
  height: 64px;
  flex-basis: 64px;
  margin: -10px 12px 0 -2px;
}

.conversation-replay-shell .message-wrapper.user .avatar img {
  width: 64px;
  height: 64px;
}

.conversation-replay-shell .msg-content {
  display: flex;
  flex-direction: column;
  max-width: 85%;
}

.conversation-current-marker {
  align-self: flex-start;
  margin: 0 0 6px;
  padding: 2px 8px;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.5;
}

.conversation-replay-shell .message-wrapper.user .conversation-current-marker {
  align-self: flex-end;
}

.conversation-current-marker.is-prompt {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}

.conversation-replay-shell .bubble {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 14px;
  box-shadow: 0 1px 2px 0 rgb(0 0 0 / 0.05);
  word-break: break-word;
  white-space: pre-wrap;
}

.conversation-replay-shell .user .bubble {
  background: linear-gradient(135deg, #2f7cf6, #0e5add);
  color: #fff;
  border-top-right-radius: 4px;
}

.conversation-replay-shell .system .bubble {
  background: #fff;
  color: #1f2937;
  border: 1px solid #e5e7eb;
  border-top-left-radius: 4px;
}

.conversation-replay-shell .message-wrapper.is-current .bubble {
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.28), 0 10px 22px rgba(37, 99, 235, 0.12);
}

.conversation-replay-shell .message-wrapper.is-current-prompt:not(.is-current) .bubble {
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.34), 0 10px 22px rgba(245, 158, 11, 0.12);
}

.conversation-artifact-list {
  display: grid;
  gap: 8px;
}

.conversation-replay-shell .advanced-dialog-entry {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
  width: min(620px, 100%);
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.06);
}

.conversation-replay-shell .advanced-dialog-entry.is-current {
  border-color: #60a5fa;
  background: #eff6ff;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12), 0 12px 26px rgba(37, 99, 235, 0.14);
}

.conversation-chart-preview {
  width: 100%;
  height: 260px;
  min-height: 260px;
  padding: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #ffffff;
}

.conversation-replay-shell .advanced-dialog-entry__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.conversation-replay-shell .advanced-dialog-entry__main {
  flex: 1 1 auto;
  min-width: 0;
  display: grid;
  gap: 4px;
}

.conversation-replay-shell .advanced-dialog-entry__type {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}

.conversation-replay-shell .advanced-dialog-entry__title {
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.4;
  word-break: break-word;
}

.conversation-replay-shell .advanced-dialog-entry__summary {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.conversation-replay-shell .advanced-dialog-entry__rule {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 6px;
  margin-top: 6px;
}

.conversation-replay-shell .advanced-dialog-entry__rule div {
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.conversation-replay-shell .advanced-dialog-entry__rule span {
  display: block;
  color: #64748b;
  font-size: 11px;
  line-height: 1.4;
}

.conversation-replay-shell .advanced-dialog-entry__rule strong {
  display: block;
  margin-top: 2px;
  color: #0f172a;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}

.conversation-replay-shell .advanced-dialog-entry__rule-explain {
  grid-column: 1 / -1;
}

.conversation-replay-shell .advanced-dialog-entry__actions {
  flex: 0 0 auto;
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 46%;
  padding-top: 0;
}

.admin-advanced-analysis-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.admin-advanced-analysis-dialog :deep(.advanced-card) {
  width: 100%;
  margin-top: 0;
}

.conversation-replay-shell .sql-block {
  margin-top: 10px;
  background: #111827;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1);
}

.conversation-replay-shell .sql-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: #1f2937;
  border-bottom: 1px solid #374151;
}

.conversation-replay-shell .sql-head .sql-title {
  padding: 0;
  border: 0;
  color: #9ca3af;
  font-size: 12px;
  font-family: monospace;
}

.conversation-replay-shell .sql-code {
  max-width: 100%;
  margin: 0;
  padding: 12px;
  overflow-x: auto;
  color: #a7f3d0;
  font-size: 13px;
  line-height: 1.6;
}

.conversation-replay-shell .thinking-details {
  width: min(620px, 100%);
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.conversation-replay-shell .thinking-details summary {
  cursor: pointer;
  color: #374151;
  font-size: 13px;
}

.conversation-replay-shell .thinking-list {
  margin: 8px 0 0 18px;
  max-height: 140px;
  overflow: auto;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
}

.detail-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-dual {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.detail-table-wrap {
  overflow: hidden;
  border: 1px solid #e8eef8;
  border-radius: 8px;
}

.result-preview-stack {
  display: grid;
  gap: 12px;
}

.detail-result-section {
  gap: 14px;
  border-color: #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-result-head {
  padding-bottom: 2px;
}

.result-preview-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1.15fr) minmax(240px, 0.85fr);
  gap: 12px;
}

.result-preview-grid--sidebar {
  grid-template-columns: 1fr;
}

.detail-content > .detail-section,
.detail-content > .detail-dual,
.detail-content > .detail-panel {
  min-width: 0;
}

.result-preview-card {
  align-content: start;
}

.result-preview-card,
.result-preview-summary-card {
  position: relative;
  gap: 12px;
  padding: 14px;
  overflow: hidden;
  border-color: #dbeafe;
  background: #ffffff;
  box-shadow: 0 10px 22px rgba(44, 74, 124, 0.06);
}

.result-preview-summary-card {
  background:
    linear-gradient(135deg, rgba(239, 246, 255, 0.96) 0%, #ffffff 62%),
    #ffffff;
}

.result-preview-card-head {
  position: relative;
  z-index: 1;
  align-items: flex-start;
}

.result-preview-card-head :deep(.el-tag),
.result-preview-table-head :deep(.el-tag) {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 7px;
  font-weight: 700;
}

.result-preview-card-title {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.result-preview-card-title span {
  color: #1e3a8a;
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.result-preview-card-title small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.result-preview-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.result-preview-summary-item {
  position: relative;
  display: grid;
  gap: 7px;
  min-height: 112px;
  padding: 12px 13px 12px 15px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  min-width: 0;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
}

.result-preview-summary-item::before {
  content: "";
  position: absolute;
  inset: 12px auto 12px 0;
  width: 3px;
  border-radius: 999px;
  background: #2563eb;
}

.result-preview-summary-item:nth-child(2)::before {
  background: #0ea5e9;
}

.result-preview-summary-item:nth-child(3)::before {
  background: #22c55e;
}

.result-preview-summary-item:nth-child(4)::before {
  background: #f59e0b;
}

.result-preview-summary-item span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.result-preview-summary-item strong {
  color: #0f172a;
  font-size: 18px;
  line-height: 1.35;
  word-break: break-word;
}

.result-preview-summary-item small {
  color: #52637f;
  font-size: 12px;
  line-height: 1.6;
}

.result-preview-card--chart {
  border-color: #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
}

.result-preview-chart-shell {
  display: grid;
  align-items: stretch;
  width: 100%;
  min-height: 360px;
  height: 360px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.78) 0%, #ffffff 100%),
    #ffffff;
  overflow: hidden;
  box-sizing: border-box;
}

.result-preview-chart {
  width: 100%;
  height: 100%;
  min-height: 0;
}

.result-preview-field-tags {
  gap: 7px;
  padding: 0 2px;
}

.result-preview-field-tags :deep(.el-tag) {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 7px;
}

.result-preview-table-card {
  border-color: #dbeafe;
  background: #ffffff;
  box-shadow: 0 10px 22px rgba(44, 74, 124, 0.06);
}

.result-preview-table-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid #e8eef8;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.result-preview-table {
  width: 100%;
}

.result-preview-table :deep(.el-table__header th.el-table__cell) {
  background: #f8fbff;
  color: #1e3a8a;
  font-weight: 800;
}

.result-preview-table :deep(.el-table__body td.el-table__cell) {
  color: #334155;
}

.result-preview-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: #eff6ff;
}

.trace-panel {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
}

.audit-trace-panel {
  gap: 10px;
  border-color: #dbeafe;
  background: #ffffff;
}

.trace-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.audit-trace-panel .trace-panel-head {
  align-items: flex-start;
}

.audit-trace-panel .trace-panel-head > div {
  display: grid;
  gap: 3px;
}

.audit-trace-panel .trace-panel-head :deep(.el-tag) {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
  font-weight: 700;
}

.trace-panel-head h4 {
  margin: 0;
  color: #13213c;
  font-size: 14px;
}

.trace-panel-head small {
  color: #74829a;
  line-height: 1.5;
}

.trace-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.trace-grid--compact {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.audit-trace-panel .trace-grid--compact {
  max-height: 420px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: #bfdbfe transparent;
}

.audit-trace-panel .trace-grid--compact::-webkit-scrollbar {
  width: 8px;
}

.audit-trace-panel .trace-grid--compact::-webkit-scrollbar-track {
  background: transparent;
}

.audit-trace-panel .trace-grid--compact::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #bfdbfe;
}

.trace-item {
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border-radius: 8px;
  background: #f8fbff;
  min-width: 0;
}

.audit-trace-panel .trace-item {
  border: 1px solid #e8eef8;
  background: linear-gradient(180deg, #fbfdff 0%, #f8fbff 100%);
}

.trace-item--wide {
  grid-column: 1 / -1;
}

.trace-item span {
  color: #64748b;
  font-size: 12px;
}

.trace-item strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
}

.audit-trace-panel .trace-item strong {
  max-height: 96px;
  overflow: auto;
  padding-right: 2px;
}

.trace-value-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 150px;
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #bfdbfe transparent;
}

.trace-value-list::-webkit-scrollbar {
  width: 8px;
}

.trace-value-list::-webkit-scrollbar-track {
  background: transparent;
}

.trace-value-list::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #bfdbfe;
}

.trace-value-list li {
  position: relative;
  padding: 6px 8px 6px 18px;
  border: 1px solid #e8eef8;
  border-radius: 7px;
  background: #ffffff;
  color: #13213c;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
}

.trace-value-list li::before {
  content: "";
  position: absolute;
  top: 13px;
  left: 8px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #2563eb;
}

.trace-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.guard-summary {
  display: grid;
  gap: 7px;
}

.guard-detail-list {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.guard-detail-item {
  display: grid;
  gap: 3px;
  padding: 7px 9px;
  border-radius: 8px;
  background: #fbfdff;
  border: 1px solid #e8eef8;
  min-width: 0;
}

.guard-detail-item span {
  color: #64748b;
  font-size: 12px;
}

.guard-detail-item strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.35;
  word-break: break-word;
}

.detail-empty {
  margin: 0;
  color: #74829a;
  line-height: 1.6;
}

.history-detail-drawer :deep(.el-drawer__body) {
  flex: 1;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.history-detail-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding: 12px 18px 6px;
  border-bottom: 1px solid #e8eef8;
  background: rgba(255, 255, 255, 0.96);
}

.history-detail-drawer :deep(.el-drawer__title) {
  color: #13213c;
  font-size: 18px;
  font-weight: 700;
}

.detail-scroll {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 18px 56px;
  box-sizing: border-box;
  background: #f5f7fb;
}

.history-detail-drawer :deep(.el-table) {
  width: 100%;
}

.admin-chat-history :deep(.el-input__wrapper),
.admin-chat-history :deep(.el-select__wrapper),
.admin-chat-history :deep(.el-range-editor.el-input__wrapper) {
  min-height: 28px;
  box-shadow: 0 0 0 1px #d9e3f2 inset;
}

.admin-chat-history :deep(.el-input__inner),
.admin-chat-history :deep(.el-select__placeholder),
.admin-chat-history :deep(.el-range-input) {
  font-size: 11px;
}

.admin-chat-history :deep(.el-range-editor.el-input__wrapper) {
  width: 100%;
  padding: 0 8px;
}

.admin-chat-history :deep(.el-button) {
  min-height: 28px;
  padding: 5px 10px;
  font-size: 11px;
  border-radius: 6px;
}

.admin-chat-history :deep(.el-table) {
  font-size: 11px;
  --el-table-border-color: #edf2f8;
  --el-table-row-hover-bg-color: #f8fbff;
}

.admin-chat-history :deep(.el-table th),
.admin-chat-history :deep(.el-table td) {
  padding-top: 4px;
  padding-bottom: 4px;
}

.admin-chat-history :deep(.el-tag) {
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  font-size: 10px;
}

.admin-chat-history :deep(.el-pagination) {
  --el-pagination-button-width: 24px;
  --el-pagination-button-height: 24px;
  font-size: 11px;
}

@media (max-width: 1280px) {
  .history-dashboard-grid {
    grid-template-columns: 1fr;
  }

  .history-side-rail {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .metric-grid,
  .analysis-grid,
  .performance-summary-grid,
  .performance-grid,
  .filter-grid,
  .detail-overview,
  .detail-query-grid,
  .context-grid,
  .audit-summary-grid,
  .detail-grid,
  .result-preview-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .result-preview-summary-grid,
  .alert-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .audit-log-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .trace-grid--compact {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .graph-full-layout {
    grid-template-columns: 1fr;
  }

  .guard-detail-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .reasoning-graph-layout {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .reasoning-mini-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .audit-context-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .reasoning-timeline-scroll {
    max-height: 520px;
  }

  .filter-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .detail-layout {
    grid-template-columns: 1fr;
  }

  .detail-sidebar {
    position: static;
  }

  .detail-overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .reasoning-timeline-item {
    grid-template-columns: 16px minmax(0, 1fr);
  }

  .conversation-replay-shell .msg-content {
    max-width: calc(100% - 76px);
  }

  .detail-query-section .detail-query-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .history-hero,
  .panel-head,
  .detail-section-head,
  .detail-actions,
  .filter-actions,
  .audit-log-head {
    flex-direction: column;
    align-items: stretch;
  }

  .metric-grid,
  .analysis-grid,
  .history-side-rail,
  .performance-summary-grid,
  .performance-grid,
  .filter-grid,
  .detail-overview,
  .detail-overview-grid,
  .detail-overview-grid--compact,
  .detail-query-grid,
  .context-grid,
  .audit-summary-grid,
  .detail-grid,
  .result-preview-grid,
  .audit-log-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: 1fr;
  }

  .result-preview-summary-grid,
  .alert-summary-grid {
    grid-template-columns: 1fr;
  }

  .trace-panel-head {
    align-items: stretch;
  }

  .graph-full-canvas {
    min-height: 340px;
  }

  .guard-detail-list {
    grid-template-columns: 1fr;
  }

  .reasoning-graph-layout,
  .reasoning-mini-summary,
  .audit-context-summary {
    grid-template-columns: 1fr;
  }

  .reasoning-timeline-scroll {
    max-height: 430px;
  }

  .detail-badges {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-overview-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .conversation-replay-shell .advanced-dialog-entry__actions {
    justify-content: flex-start;
    max-width: 100%;
  }

  .conversation-replay-shell .advanced-dialog-entry__head {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-overview-group {
    padding: 10px;
  }

  .filter-field--wide,
  .filter-field--date {
    grid-column: auto;
  }

  .guard-detail-item {
    flex-direction: column;
  }

  .guard-detail-item strong {
    text-align: left;
  }

  .hot-list-item {
    flex-direction: column;
  }

  .hot-list-meta {
    text-align: left;
  }

  .table-footer {
    justify-content: center;
    flex-direction: column;
    align-items: stretch;
  }
}

@media (max-width: 480px) {
  .detail-badges {
    grid-template-columns: 1fr;
  }
}
</style>
