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
                  <small>{{ chartTypeLabel(row.chartType) }} / {{ row.executionTimeMs ?? '-' }} ms / {{ row.isHitCacheLabel || '缓存未知' }}</small>
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
            <el-table-column label="AI 分析" width="92">
              <template #default="{ row }">
                <el-tag size="small" :type="aiParseTagType(row.aiParseResult)">{{ row.aiParseResultLabel }}</el-tag>
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
                    <div class="detail-badges">
                      <el-tag :type="statusTagType(detail.executionStatus)">{{ detail.executionStatusLabel }}</el-tag>
                      <el-tag :type="riskTagType(detail.riskLevel)">{{ detail.riskLevel }}</el-tag>
                      <el-tag type="info">{{ sourceTypeLabel(detail.sourceType) }}</el-tag>
                      <el-tag type="info">{{ chartTypeLabel(detail.chartType) }}</el-tag>
                      <el-tag v-if="shouldShowDetailSql" :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                      <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ detail.aiParseResultLabel || '解析信息缺失' }}</el-tag>
                      <el-tag :type="detail.isHitCache ? 'success' : 'info'">{{ detail.isHitCacheLabel || '缓存未知' }}</el-tag>
                      <el-tag :type="detail.slowQuery ? 'warning' : 'info'">{{ detail.slowQuery ? '慢查询' : '非慢查询' }}</el-tag>
                    </div>
                    <h2>{{ detail.question || '未记录原始问题' }}</h2>
                    <p>{{ detailHeroSummary }}</p>
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
                            <small>{{ detail.aiParseResultLabel || '解析信息缺失' }}</small>
                          </div>
                        </div>
                        <div class="detail-overview-row">
                          <span>图表类型</span>
                          <div class="detail-overview-value">
                            <strong>{{ chartTypeLabel(detail.chartType) }}</strong>
                            <small>{{ detail.intentType || '数据分析' }}</small>
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
                <div class="detail-grid detail-grid--audit detail-grid--sidebar">
                  <div class="detail-card detail-card--blue">
                    <span>审计记录</span>
                    <strong>{{ detail.auditSummary.count || 0 }}</strong>
                  </div>
                  <div class="detail-card detail-card--red">
                    <span>拦截</span>
                    <strong>{{ detail.auditSummary.blockedCount || 0 }}</strong>
                  </div>
                  <div class="detail-card detail-card--amber">
                    <span>告警</span>
                    <strong>{{ detail.auditSummary.warnCount || 0 }}</strong>
                  </div>
                  <div class="detail-card detail-card--green">
                    <span>慢查询</span>
                    <strong>{{ detail.auditSummary.slowCount || 0 }}</strong>
                  </div>
                </div>
              </section>

            </aside>

            <main class="detail-content">
              <el-tabs v-model="detailActiveTab" class="detail-tabs">
                <el-tab-pane label="查询与结果" name="query">
                  <div class="detail-tab-stack">
                    <section class="detail-section detail-panel">
                      <div class="detail-section-head">
                        <div class="detail-section-title">
                          <h3>查询内容</h3>
                          <p>{{ detailQueryContentSubtitle }}</p>
                        </div>
                        <div class="detail-actions">
                          <el-button type="primary" :loading="rerunning" @click="rerun(detail)">重新执行</el-button>
                          <el-button v-if="shouldShowDetailSql" :disabled="!detailRealSql" @click="copySql(detailRealSql)">复制 SQL</el-button>
                        </div>
                      </div>
                      <div class="detail-query-grid" :class="{ 'detail-query-grid--single': !shouldShowDetailSql }">
                        <article class="detail-subpanel">
                          <div class="detail-subpanel-head">
                            <span>原始问题</span>
                          </div>
                          <p class="detail-text">{{ detail.question || '未记录原始问题' }}</p>
                        </article>
                        <article v-if="shouldShowDetailSql" class="detail-subpanel detail-subpanel--code">
                          <div class="detail-subpanel-head">
                            <span>生成 SQL</span>
                          </div>
                          <pre class="detail-code">{{ detailSqlDisplayText }}</pre>
                        </article>
                      </div>
                    </section>

                    <section class="detail-section detail-panel">
                      <div class="detail-section-head">
                        <div class="detail-section-title">
                          <h3>对话摘要</h3>
                          <p>帮助管理员快速判断这次查询是如何被解析、生成和执行的</p>
                        </div>
                      </div>
                      <div class="context-grid">
                        <article class="context-card context-card--wide">
                          <div class="context-card-head">
                            <span>摘要说明</span>
                            <el-tag effect="plain">{{ detail.intentType || '数据分析' }}</el-tag>
                          </div>
                          <strong>{{ detailQuerySummaryText }}</strong>
                          <p>{{ detail.riskReason || '未记录额外执行说明。' }}</p>
                        </article>
                        <article class="context-card">
                          <div class="context-card-head">
                            <span>解析结果</span>
                            <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ detail.aiParseResultLabel || '解析信息缺失' }}</el-tag>
                          </div>
                          <strong>{{ detailParseEngineText }}</strong>
                          <p>{{ detailParseDescription }}</p>
                        </article>
                        <article v-if="shouldShowDetailSql" class="context-card">
                          <div class="context-card-head">
                            <span>SQL 生成状态</span>
                            <el-tag :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                          </div>
                          <strong>{{ detail.executionStatusLabel || '-' }}</strong>
                          <p>{{ detailSqlStatusDescription }}</p>
                        </article>
                      </div>
                    </section>

                    <section v-if="detailHasResultPreview" class="detail-section detail-panel">
                      <div class="detail-section-head">
                        <div class="detail-section-title">
                          <h3>结果预览</h3>
                          <p>图表缩略图、结果概览与保留样例</p>
                        </div>
                      </div>
                      <div class="result-preview-stack">
                        <article class="context-card result-preview-summary-card">
                          <div class="context-card-head">
                            <span>结果概览</span>
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
                          <div class="context-card-head">
                            <span>图表缩略图</span>
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
                          <div v-if="detailFieldMappingSummary.length" class="trace-tags">
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
                      <div v-if="detail.snapshotPreviewRows?.length" class="detail-table-wrap">
                        <el-table :data="detail.snapshotPreviewRows" size="small" border>
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
                      <div class="context-grid">
                        <article class="context-card">
                          <div class="context-card-head">
                            <span>权限校验结果</span>
                            <el-tag :type="permissionStatusTagType(detail.permissionCheck?.status)">
                              {{ detail.permissionCheck?.label || '未记录' }}
                            </el-tag>
                          </div>
                          <strong>{{ detail.permissionCheck?.message || '未记录独立权限校验结果' }}</strong>
                          <p>{{ detail.permissionCheck?.detail || '当前详情仍沿用查询主链路的权限控制，但历史中没有保留更细粒度的校验快照。' }}</p>
                          <div class="context-meta">
                            <span>授权作用域表</span>
                            <strong>{{ detail.permissionCheck?.scopeTableName || detail.queryTableName || '-' }}</strong>
                          </div>
                        </article>
                        <article class="context-card">
                          <div class="context-card-head">
                            <span>缓存上下文</span>
                            <el-tag :type="detail.cacheContext?.cacheHit ? 'success' : 'info'">
                              {{ detail.cacheContext?.cacheHitLabel || '未命中缓存' }}
                            </el-tag>
                          </div>
                          <strong>{{ detail.cacheContext?.cacheAuditStatus || '未记录缓存审计结论' }}</strong>
                          <p>{{ detail.cacheContext?.redisStatusText || '当前审计日志未返回 Redis 侧状态。' }}</p>
                          <div class="context-meta">
                            <span>缓存 Key</span>
                            <strong>{{ detail.cacheContext?.cacheKey || '未记录缓存 Key' }}</strong>
                          </div>
                          <div class="context-meta">
                            <span>执行保护动作</span>
                            <strong>{{ detail.cacheContext?.queryGuardActionLabel || '未触发执行保护' }}</strong>
                          </div>
                        </article>
                        <article class="context-card context-card--wide">
                          <div class="context-card-head">
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
                        <article v-if="shouldShowDetailSql && detail.cacheContext?.cacheSql" class="context-card context-card--wide context-card--code">
                          <div class="context-card-head">
                            <span>缓存复用 SQL</span>
                            <el-tag effect="plain">缓存回放</el-tag>
                          </div>
                          <pre class="detail-code detail-code--light">{{ detail.cacheContext.cacheSql }}</pre>
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
                              <el-tag :type="riskTagType(log.riskLevel)">{{ log.riskLevel }}</el-tag>
                              <el-tag :type="statusTextType(log.executeStatus)">{{ log.executeStatus }}</el-tag>
                              <span>{{ log.createdAt }}</span>
                            </div>
                            <p class="audit-log-reason">{{ log.riskReason || '通过基础安全检测' }}</p>
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
                          <div class="trace-panel">
                            <div class="trace-panel-head">
                              <h4>SQL 生成轨迹</h4>
                              <small>缓存命中、SQL 生成引擎与执行结果</small>
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
                                <strong v-else>{{ item.value }}</strong>
                              </div>
                            </div>
                            <p v-else class="detail-empty">无生成轨迹</p>
                          </div>

                          <div class="trace-panel">
                            <div class="trace-panel-head">
                              <h4>知识图谱匹配摘要</h4>
                              <small>命中节点、字段标签与映射提示来源</small>
                            </div>
                            <div v-if="log.kgMatchLogItems?.length" class="trace-grid trace-grid--compact">
                              <div
                                v-for="item in log.kgMatchLogItems"
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
                                <strong v-else>{{ item.value }}</strong>
                              </div>
                            </div>
                            <p v-else class="detail-empty">无知识图谱匹配日志</p>
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
                          </div>
                        </div>
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
                                <span class="reasoning-stepno">STEP {{ index + 1 }}</span>
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
                      </section>

                      <section class="detail-section detail-panel" v-if="detail.graphContext?.length">
                        <div class="detail-section-head">
                          <div class="detail-section-title">
                            <h3>知识图谱上下文</h3>
                            <p>本次查询引用的图谱节点与字段内容</p>
                          </div>
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
              </el-tabs>
            </main>
          </div>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, RefreshRight, View } from '@element-plus/icons-vue'
import { API_BASE, http } from '../../api/http'
import DashboardChart from '../../components/dashboard/DashboardChart.vue'
import LegacyInlineChart from '../../components/dashboard/LegacyInlineChart.vue'
import {
  deleteAdminChatHistoryBatch,
  fetchAdminChatHistory,
  fetchAdminChatHistoryAnalytics,
  fetchAdminChatHistoryDetail,
  rerunAdminChatHistory
} from '../../api/adminChatHistory'

const loading = ref(false)
const exporting = ref(false)
const rerunning = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const detailActiveTab = ref('query')
const rows = ref([])
const selectedIds = ref([])
const summary = ref({})
const governance = ref({})
const analytics = ref({
  trends: {},
  performance: {}
})
const page = ref(1)
const pageSize = ref(10)
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
  if (!text || upper === 'UNKNOWN' || upper === 'UNKONWN') return ''
  if (['预测算法', '情景推演', '智能预警'].includes(text)) return ''
  return text
}

const advancedAnalysisKind = (entry = detail.value) => {
  const snapshot = getDetailChartSnapshot(entry)
  const rawType = String(
    snapshot.advancedAnalysisType ||
    snapshot.type ||
    snapshot.fieldMapping?.mappingType ||
    ''
  ).trim()
  const normalized = rawType.replace(/[-_\s]/g, '').toLowerCase()
  if (normalized.includes('whatif')) return 'whatIf'
  if (normalized.includes('alert') || normalized.includes('warning') || normalized.includes('prewarning')) return 'alert'
  if (normalized.includes('forecast')) return 'forecast'
  if (snapshot.forecastMeta || isForecastDetailSnapshot(entry)) return 'forecast'
  if (snapshot.whatIfMeta || snapshot.scenarioMeta || Array.isArray(snapshot.scenarios) || Array.isArray(snapshot.variables)) return 'whatIf'
  if (snapshot.alertMeta || snapshot.alertRule || snapshot.ruleId) return 'alert'
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
  if (!Array.isArray(snapshot.data) || !snapshot.data.length) return null
  return {
    chartType: current.chartType || snapshot.chartType,
    chartSnapshot: snapshot
  }
})

const detailHasResultPreview = computed(() => Boolean(detailChartPayload.value || detail.value?.snapshotPreviewRows?.length))

const detailFieldMappingSummary = computed(() => summarizeFieldMapping(
  detail.value?.snapshotMetrics?.fieldMapping || getDetailChartSnapshot(detail.value)?.fieldMapping
))

const detailForecastMeta = computed(() => {
  const snapshot = getDetailChartSnapshot(detail.value)
  const meta = snapshot.forecastMeta
  return meta && typeof meta === 'object' ? meta : {}
})

const detailParseEngineText = computed(() => {
  const kind = advancedAnalysisKind(detail.value)
  if (kind === 'forecast') return detailForecastMeta.value.algorithm || '时序预测算法'
  if (kind === 'whatIf') return '拟合推演算法'
  if (kind === 'alert') return 'Z-Score 异常检测'
  return normalizeModelCategoryText(detail.value?.engine) ||
    normalizeModelCategoryText(detail.value?.modelCategory) ||
    '未记录解析引擎'
})

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
    return '本次请求命中 What-if 推演流程，由拟合算法或业务公式直接计算情景结果，因此详情中不展示 SQL。'
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
      value: current.intentType || '数据分析',
      hint: '模型对本次问句识别出的主要任务类型'
    },
    {
      label: '数据定位',
      value: current.queryTableName || current.permissionCheck?.scopeTableName || '未记录数据范围',
      hint: '本次生成过程中主要引用的数据表或数据源'
    },
    {
      label: isAdvanced ? '算法策略' : '生成策略',
      value: detailParseEngineText.value,
      hint: current.aiParseResultLabel || '未记录解析结果'
    },
    {
      label: '结果产物',
      value: chartTypeLabel(current.chartType),
      hint: isAdvanced ? (advancedAnalysisModelLabel(current) || '算法产物') : (current.sqlStatusLabel || '未记录 SQL 状态')
    }
  ]
})

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
  SAFE: '安全',
  WARN: '告警',
  BLOCKED: '拦截',
  ALLOW: '允许',
  SUCCESS: '成功',
  FAILED: '失败',
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
const wideKeys = new Set(['activeTable', 'queryGuard', 'error', 'labels', 'hintKeys'])

const formatTraceValue = (value) => {
  const text = String(value ?? '').trim()
  if (!text) return '-'
  return traceValueMap[text] || text
}

const splitTraceTokens = (key, value) => {
  if (!tokenKeys.has(key)) return []
  return String(value || '')
    .split(key === 'labels' ? '|' : ',')
    .map(item => item.trim())
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
    if (!nestedKey) return formatTraceValue(part)
    return `${traceLabelMap[nestedKey] || nestedKey}：${formatTraceValue(nestedValue)}`
  }).join('；')
}

const parseTraceLine = (line, prefix) => {
  const trimmed = String(line || '').trim()
  if (!trimmed) return []
  return trimmed
    .split(';')
    .map(part => part.trim())
    .filter(Boolean)
    .map((part, index) => {
      const [rawKey, ...rest] = part.split('=')
      const key = rawKey?.trim()
      const value = rest.join('=').trim()
      if (!key) return null
      const tokens = splitTraceTokens(key, value)
      const compoundValue = tokens.length ? '' : formatCompoundValue(value)
      return {
        id: `${prefix}-${key}-${index}`,
        key,
        label: traceLabelMap[key] || key,
        value: compoundValue || formatTraceValue(value),
        tokens,
        wide: wideKeys.has(key) || tokens.length > 4
      }
    })
    .filter(Boolean)
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
  table: '表格'
})[String(value || '').toLowerCase()] || (value || '未识别图表')

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

const normalizeGuardItem = (item) => {
  if (!item) return null
  return {
    ...item,
    value: item.key === 'timeoutMs'
      ? `${item.value} ms`
      : item.key === 'maxRows' || item.key === 'maxScanRows' || item.key === 'explainRows'
        ? `${item.value} 行`
        : item.value
  }
}

const parseExecutionGuard = (text, prefix) => parseTraceLine(text, prefix)
  .map(normalizeGuardItem)
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
    generationTraceItems: parseTraceText(log.generationTrace, `gen-${log.id || index}`),
    kgMatchLogItems: parseTraceText(log.kgMatchLog, `kg-${log.id || index}`)
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
    analytics.value = analyticsData || { trends: {}, performance: {} }
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
    detail.value = normalizeDetail(await fetchAdminChatHistoryDetail(row.id))
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
      detail.value = normalizeDetail(await fetchAdminChatHistoryDetail(row.id))
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
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
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
  max-width: 340px;
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
  background: #f8fbff;
}

.detail-overview-shell {
  gap: 12px;
}

.detail-overview-main {
  align-content: start;
  gap: 8px;
}

.detail-panel--muted .detail-overview-main h2 {
  font-size: 18px;
}

.detail-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  overflow: visible;
  padding-bottom: 2px;
  scrollbar-width: thin;
}

.detail-overview-main h2 {
  margin: 0;
  color: #0f172a;
  font-size: 20px;
  line-height: 1.35;
}

.detail-overview-main p {
  margin: 0;
  color: #52637f;
  line-height: 1.7;
  font-size: 13px;
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
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid #edf2f7;
}

.detail-overview-group-title {
  color: #5b6c86;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.detail-overview-list {
  display: grid;
  gap: 0;
}

.detail-overview-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 9px 0;
  border-top: 1px dashed #e5edf7;
}

.detail-overview-row:first-child {
  border-top: none;
  padding-top: 0;
}

.detail-overview-row:last-child {
  padding-bottom: 0;
}

.detail-overview-row > span {
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.detail-overview-value {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.detail-overview-value strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.detail-overview-value small {
  color: #74829a;
  font-size: 12px;
  line-height: 1.5;
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

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.detail-grid--audit {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.detail-grid--sidebar {
  grid-template-columns: 1fr;
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

.detail-card {
  display: grid;
  gap: 6px;
  padding: 11px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.detail-card span {
  color: #64748b;
  font-size: 12px;
}

.detail-card strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.detail-card small {
  color: #74829a;
  font-size: 12px;
  word-break: break-word;
}

.detail-card--blue {
  background: #f5f9ff;
}

.detail-card--red {
  background: #fff3f2;
}

.detail-card--amber {
  background: #fff8eb;
}

.detail-card--green {
  background: #f1fbf5;
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
  gap: 10px;
  padding: 12px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
  background: #fbfdff;
}

.audit-log-list {
  display: grid;
  gap: 10px;
}

.audit-log-summary {
  display: grid;
  gap: 5px;
}

.audit-log-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}

.audit-log-reason {
  margin: 0;
  color: #42546f;
  font-size: 13px;
  line-height: 1.5;
}

.audit-log-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: start;
  gap: 8px;
}

.audit-log-metric {
  display: grid;
  gap: 4px;
  min-height: 72px;
  padding: 9px 10px;
  border-radius: 8px;
  background: #f8fbff;
  border: 1px solid #edf2f7;
}

.audit-log-metric--wide {
  grid-column: 1 / -1;
  min-height: 0;
}

.audit-log-grid span {
  color: #64748b;
  font-size: 12px;
}

.audit-log-grid strong {
  color: #13213c;
  font-size: 13px;
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

.result-preview-summary-card {
  gap: 10px;
  padding: 10px 12px;
  background: #ffffff;
}

.result-preview-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.result-preview-summary-item {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #f8fbff;
  min-width: 0;
}

.result-preview-summary-item span {
  color: #64748b;
  font-size: 12px;
}

.result-preview-summary-item strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.4;
  word-break: break-word;
}

.result-preview-summary-item small {
  color: #74829a;
  font-size: 12px;
  line-height: 1.5;
}

.result-preview-card--chart {
  background: #ffffff;
}

.result-preview-chart-shell {
  display: grid;
  align-items: stretch;
  width: 100%;
  min-height: 300px;
  height: 300px;
  padding: 10px 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
  overflow: hidden;
  box-sizing: border-box;
}

.result-preview-chart {
  width: 100%;
  height: 100%;
  min-height: 0;
}

.trace-panel {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
}

.trace-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
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

.trace-item {
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border-radius: 8px;
  background: #f8fbff;
  min-width: 0;
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
  background: #f8fbff;
  border: 1px solid #edf2f7;
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
  .detail-grid,
  .detail-grid--audit,
  .result-preview-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .result-preview-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .audit-log-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .trace-grid--compact {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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

  .detail-overview-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .reasoning-timeline-item {
    grid-template-columns: 16px minmax(0, 1fr);
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
  .detail-grid,
  .detail-grid--audit,
  .result-preview-grid,
  .audit-log-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: 1fr;
  }

  .result-preview-summary-grid {
    grid-template-columns: 1fr;
  }

  .trace-panel-head {
    align-items: stretch;
  }

  .guard-detail-list {
    grid-template-columns: 1fr;
  }

  .reasoning-graph-layout,
  .reasoning-mini-summary {
    grid-template-columns: 1fr;
  }

  .detail-badges {
    flex-wrap: wrap;
    overflow-x: visible;
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
</style>
