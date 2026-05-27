<template>
  <section class="admin-chat-history">
    <header class="history-hero">
      <div>
        <h1>管理员对话历史</h1>
        <p>查看全平台查询轨迹，联动 SQL 审计、知识图谱日志、模型推理过程与性能表现。</p>
        <small v-if="governance.policyText" class="hero-policy">{{ governance.policyText }}</small>
      </div>
      <div class="hero-actions">
        <el-button :loading="exporting" @click="exportExcel">导出 Excel</el-button>
        <el-button type="danger" :disabled="!selectedIds.length" @click="deleteSelected">批量删除</el-button>
      </div>
    </header>

    <div class="metric-grid">
      <article class="metric-card metric-card--blue">
        <span>总记录数</span>
        <strong>{{ summary.total || 0 }}</strong>
        <small>成功率 {{ formatPercent(summary.successRate) }}</small>
      </article>
      <article class="metric-card metric-card--orange metric-card--stacked">
        <div class="metric-card__main">
          <span>今日查询</span>
          <strong>{{ summary.todayCount || 0 }}</strong>
        </div>
        <div class="metric-card__meta">
          <small>风险拦截 {{ summary.blockedCount || 0 }}</small>
          <small>告警 {{ summary.warnCount || 0 }}</small>
        </div>
      </article>
      <article class="metric-card metric-card--green">
        <span>缓存命中</span>
        <strong>{{ formatPercent(summary.cacheHitRate) }}</strong>
        <small>命中数 {{ summary.cacheHitCount || 0 }}</small>
      </article>
      <article class="metric-card metric-card--red">
        <span>慢查询</span>
        <strong>{{ summary.slowCount || 0 }}</strong>
        <small>平均耗时 {{ summary.avgDurationMs || 0 }} ms</small>
      </article>
    </div>

    <section class="history-panel">
      <div class="panel-head">
        <div>
          <h2>筛选条件</h2>
          <p>支持按用户、时间、风险、数据源、模型、状态和缓存情况组合筛选。</p>
        </div>
      </div>
      <div class="filter-grid">
        <el-input v-model.trim="filters.keyword" placeholder="搜索问题 / SQL / 用户" clearable @keyup.enter="loadList(1)" />
        <el-input v-model.trim="filters.userId" placeholder="用户ID / 用户名 / 昵称" clearable @keyup.enter="loadList(1)" />
        <el-input v-model.trim="filters.tableName" placeholder="数据源 / 表名" clearable @keyup.enter="loadList(1)" />
        <el-select v-model="filters.sourceType" clearable placeholder="数据源类型">
          <el-option label="上传数据" value="UPLOAD" />
          <el-option label="官方数据源" value="OFFICIAL" />
        </el-select>
        <el-select v-model="filters.chartType" clearable placeholder="图表类型">
          <el-option label="柱状图" value="bar" />
          <el-option label="折线图" value="line" />
          <el-option label="饼图" value="pie" />
          <el-option label="表格" value="table" />
        </el-select>
        <el-select v-model="filters.riskLevel" clearable placeholder="风险等级">
          <el-option label="安全" value="SAFE" />
          <el-option label="告警" value="WARN" />
          <el-option label="拦截" value="BLOCKED" />
        </el-select>
        <el-select v-model="filters.executionStatus" clearable placeholder="执行状态">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="取消" value="CANCELLED" />
        </el-select>
        <el-input v-model.trim="filters.modelType" placeholder="模型类型" clearable @keyup.enter="loadList(1)" />
        <el-select v-model="filters.cacheHit" clearable placeholder="缓存命中">
          <el-option label="命中" :value="true" />
          <el-option label="未命中" :value="false" />
        </el-select>
        <el-select v-model="filters.slowQuery" clearable placeholder="慢查询">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          unlink-panels
        />
        <div class="filter-actions">
          <el-button @click="resetFilters">重置</el-button>
          <el-button type="primary" :loading="loading" @click="loadList(1)">查询</el-button>
        </div>
      </div>
    </section>

    <section class="history-panel">
      <div class="panel-head">
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
          <LegacyInlineChart
            title=""
            chart-type="line"
            :data="queryTrendPoints"
          />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>风险趋势</h3>
              <p>按天观察拦截与告警累计变化</p>
            </div>
          </div>
          <LegacyInlineChart
            title=""
            chart-type="bar"
            :data="riskTrendPoints"
          />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>缓存命中趋势</h3>
              <p>按天查看缓存命中次数变化</p>
            </div>
          </div>
          <LegacyInlineChart
            title=""
            chart-type="line"
            :data="cacheTrendPoints"
          />
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>慢查询趋势</h3>
              <p>按天回看慢查询波动</p>
            </div>
          </div>
          <LegacyInlineChart
            title=""
            chart-type="bar"
            :data="slowTrendPoints"
          />
        </article>
      </div>
    </section>

    <section class="history-panel">
      <div class="panel-head">
        <div>
          <h2>性能治理</h2>
          <p>定位高频慢查询与缓存未命中的热点问题。</p>
        </div>
      </div>
      <div class="performance-summary-grid">
        <article class="detail-card detail-card--red">
          <span>慢查询率</span>
          <strong>{{ formatPercent(analytics.performance?.summary?.slowQueryRate) }}</strong>
          <small>慢查询 {{ analytics.performance?.summary?.slowCount || 0 }} 条</small>
        </article>
        <article class="detail-card detail-card--green">
          <span>缓存命中率</span>
          <strong>{{ formatPercent(analytics.performance?.summary?.cacheHitRate) }}</strong>
          <small>未命中 {{ analytics.performance?.summary?.cacheMissCount || 0 }} 条</small>
        </article>
        <article class="detail-card detail-card--amber">
          <span>平均耗时</span>
          <strong>{{ analytics.performance?.summary?.avgDurationMs || 0 }} ms</strong>
          <small>最大耗时 {{ analytics.performance?.summary?.maxDurationMs || 0 }} ms</small>
        </article>
        <article class="detail-card detail-card--blue">
          <span>风险查询</span>
          <strong>{{ analytics.performance?.summary?.riskCount || 0 }}</strong>
          <small>总查询 {{ analytics.performance?.summary?.totalCount || 0 }} 条</small>
        </article>
      </div>
      <div class="performance-grid">
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>高频慢查询</h3>
              <p>同问题维度聚合后的慢查询热点</p>
            </div>
          </div>
          <div class="hot-list" v-if="analytics.performance?.slowQueryGroups?.length">
            <div
              v-for="(item, index) in analytics.performance.slowQueryGroups"
              :key="`slow-group-${index}`"
              class="hot-list-item"
            >
              <div class="hot-list-main">
                <strong>{{ item.question || '未记录问题' }}</strong>
                <small>{{ item.queryTableName || '未指定数据源' }}</small>
              </div>
              <div class="hot-list-meta">
                <span>{{ item.hitCount || 0 }} 次</span>
                <strong>{{ item.avgDurationMs || 0 }} ms</strong>
              </div>
            </div>
          </div>
          <p v-else class="detail-empty">当前筛选范围内没有高频慢查询。</p>
        </article>
        <article class="analysis-card">
          <div class="analysis-card-head">
            <div>
              <h3>缓存未命中热点</h3>
              <p>优先优化重复查询却未命中的场景</p>
            </div>
          </div>
          <div class="hot-list" v-if="analytics.performance?.cacheMissGroups?.length">
            <div
              v-for="(item, index) in analytics.performance.cacheMissGroups"
              :key="`cache-group-${index}`"
              class="hot-list-item"
            >
              <div class="hot-list-main">
                <strong>{{ item.question || '未记录问题' }}</strong>
                <small>{{ item.queryTableName || '未指定数据源' }}</small>
              </div>
              <div class="hot-list-meta">
                <span>{{ item.missCount || 0 }} 次</span>
                <strong>{{ item.avgDurationMs || 0 }} ms</strong>
              </div>
            </div>
          </div>
          <p v-else class="detail-empty">当前筛选范围内没有缓存未命中热点。</p>
        </article>
      </div>
    </section>

    <section class="history-panel">
      <div class="panel-head">
        <div>
          <h2>对话列表</h2>
          <p>共 {{ total }} 条，当前页 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }}</p>
        </div>
        <div class="panel-actions">
          <el-button @click="toggleSort">{{ filters.sortDirection === 'DESC' ? '时间倒序' : '时间正序' }}</el-button>
          <el-button :loading="loading" @click="loadList(page)">刷新</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        class="history-table"
        border
        @selection-change="handleSelectionChange"
        @row-click="openDetail"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column prop="createdAt" label="时间" min-width="160" />
        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">
            <div class="user-cell">
              <strong>{{ row.operatorLabel || row.userId }}</strong>
              <small>{{ row.userId }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="查询问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="queryTableName" label="数据源" min-width="140" show-overflow-tooltip />
        <el-table-column prop="chartType" label="图表" width="90" />
        <el-table-column prop="modelCategory" label="模型" width="96" show-overflow-tooltip />
        <el-table-column label="SQL 状态" width="106">
          <template #default="{ row }">
            <el-tag :type="sqlStatusTagType(row.sqlStatus)">{{ row.sqlStatusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="AI 解析" width="118">
          <template #default="{ row }">
            <el-tag :type="aiParseTagType(row.aiParseResult)">{{ row.aiParseResultLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.executionStatus)">{{ row.executionStatusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="90">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缓存" width="90">
          <template #default="{ row }">
            <el-tag :type="row.isHitCache ? 'success' : 'info'">{{ row.isHitCacheLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="慢查询" width="90">
          <template #default="{ row }">
            <el-tag :type="row.slowQuery ? 'warning' : 'info'">{{ row.slowQuery ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executionTimeMs" label="耗时" width="90">
          <template #default="{ row }">{{ row.executionTimeMs ?? '-' }} ms</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click.stop="rerun(row)">复跑</el-button>
            <el-button link type="danger" @click.stop="deleteOne(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
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

    <el-drawer
      v-model="detailVisible"
      title="对话详情"
      size="62%"
      destroy-on-close
      append-to-body
      class="history-detail-drawer"
    >
      <div class="detail-scroll">
        <div v-if="detail" class="detail-wrap detail-wrap--drawer">
          <section class="detail-overview">
            <div class="detail-panel detail-panel--muted detail-overview-main">
              <div class="detail-badges">
                <el-tag :type="statusTagType(detail.executionStatus)">{{ detail.executionStatusLabel }}</el-tag>
                <el-tag :type="riskTagType(detail.riskLevel)">{{ detail.riskLevel }}</el-tag>
                <el-tag type="info">{{ sourceTypeLabel(detail.sourceType) }}</el-tag>
                <el-tag type="info">{{ chartTypeLabel(detail.chartType) }}</el-tag>
                <el-tag :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ detail.aiParseResultLabel || '解析信息缺失' }}</el-tag>
                <el-tag :type="detail.isHitCache ? 'success' : 'info'">{{ detail.isHitCacheLabel || '缓存未知' }}</el-tag>
                <el-tag :type="detail.slowQuery ? 'warning' : 'info'">{{ detail.slowQuery ? '慢查询' : '非慢查询' }}</el-tag>
              </div>
              <h2>{{ detail.question || '未记录原始问题' }}</h2>
              <p>{{ detail.riskReason || '本次查询已完成执行，未记录额外风险原因。' }}</p>
            </div>

            <div class="detail-panel detail-overview-side">
              <div class="detail-overview-grid">
                <div class="detail-overview-item">
                  <span>查询用户</span>
                  <strong>{{ detail.operator?.displayName || detail.operatorLabel || detail.userId }}</strong>
                  <small>{{ detail.userId }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>发生时间</span>
                  <strong>{{ detail.createdAt || '-' }}</strong>
                  <small>第 {{ detail.turnNo || '-' }} 轮</small>
                </div>
                <div class="detail-overview-item">
                  <span>数据源</span>
                  <strong>{{ detail.queryTableName || '-' }}</strong>
                  <small>{{ sourceTypeLabel(detail.sourceType) }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>模型类型</span>
                  <strong>{{ detail.modelCategory || '未识别模型' }}</strong>
                  <small>{{ detail.artifactType || 'CHART' }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>解析引擎</span>
                  <strong>{{ detail.engine || '未记录解析引擎' }}</strong>
                  <small>{{ detail.aiParseResultLabel || '解析信息缺失' }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>图表类型</span>
                  <strong>{{ chartTypeLabel(detail.chartType) }}</strong>
                  <small>{{ detail.intentType || '数据分析' }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>执行耗时</span>
                  <strong>{{ formatDuration(detail.executionTimeMs) }}</strong>
                  <small>{{ detail.conversationId || '未记录会话ID' }}</small>
                </div>
                <div class="detail-overview-item">
                  <span>SQL 状态</span>
                  <strong>{{ detail.sqlStatusLabel || '无 SQL' }}</strong>
                  <small>{{ detail.executionStatusLabel || '-' }}</small>
                </div>
              </div>
            </div>
          </section>

          <section class="detail-section detail-panel">
            <div class="detail-section-head">
              <div class="detail-section-title">
                <h3>查询内容</h3>
                <p>原始问句与生成 SQL</p>
              </div>
              <div class="detail-actions">
                <el-button type="primary" :loading="rerunning" @click="rerun(detail)">重新执行</el-button>
                <el-button @click="copySql(detail.generatedSql || detail.sql)">复制 SQL</el-button>
              </div>
            </div>
            <div class="detail-query-grid">
              <article class="detail-subpanel">
                <div class="detail-subpanel-head">
                  <span>原始问题</span>
                </div>
                <p class="detail-text">{{ detail.question || '未记录原始问题' }}</p>
              </article>
              <article class="detail-subpanel detail-subpanel--code">
                <div class="detail-subpanel-head">
                  <span>生成 SQL</span>
                </div>
                <pre class="detail-code">{{ detail.generatedSql || detail.sql || '无 SQL 记录' }}</pre>
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
                <strong>{{ detail.summaryText || '当前记录未生成摘要，建议结合原始问题、SQL 和审计日志一起查看。' }}</strong>
                <p>{{ detail.riskReason || '未记录额外执行说明。' }}</p>
              </article>
              <article class="context-card">
                <div class="context-card-head">
                  <span>解析结果</span>
                  <el-tag :type="aiParseTagType(detail.aiParseResult)">{{ detail.aiParseResultLabel || '解析信息缺失' }}</el-tag>
                </div>
                <strong>{{ detail.engine || detail.modelCategory || '未记录解析引擎' }}</strong>
                <p>{{ detail.generatedSql || detail.sql ? '本次请求已落库生成 SQL，可继续结合审计日志追溯。' : '当前记录未保存 SQL 文本，通常意味着解析未完成或流程被提前中断。' }}</p>
              </article>
              <article class="context-card">
                <div class="context-card-head">
                  <span>SQL 生成状态</span>
                  <el-tag :type="sqlStatusTagType(detail.sqlStatus)">{{ detail.sqlStatusLabel || '无 SQL' }}</el-tag>
                </div>
                <strong>{{ detail.executionStatusLabel || '-' }}</strong>
                <p>{{ detail.sqlStatus === 'BLOCKED' ? 'SQL 已生成，但在审计或权限校验阶段被拦截。' : detail.sqlStatus === 'READY' ? 'SQL 已成功生成并进入执行链路。' : '当前记录没有完整 SQL 结果。' }}</p>
              </article>
            </div>
          </section>

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
              <article v-if="detail.cacheContext?.cacheSql" class="context-card context-card--wide context-card--code">
                <div class="context-card-head">
                  <span>缓存复用 SQL</span>
                  <el-tag effect="plain">缓存回放</el-tag>
                </div>
                <pre class="detail-code detail-code--light">{{ detail.cacheContext.cacheSql }}</pre>
              </article>
            </div>
          </section>

          <section class="detail-section detail-panel" v-if="detail.auditSummary">
            <div class="detail-section-head">
              <div class="detail-section-title">
                <h3>安全审计摘要</h3>
                <p>风险拦截、告警与慢查询概览</p>
              </div>
            </div>
            <div class="detail-grid detail-grid--audit">
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

          <section class="detail-section detail-panel" v-if="detail.auditLogs?.length">
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
                  <div>
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
                  <div>
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
                  <div>
                    <span>缓存审计</span>
                    <strong>{{ log.cacheAuditStatus || '-' }}</strong>
                  </div>
                  <div>
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
                  <div v-if="log.generationTraceItems?.length" class="trace-grid">
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
                  <div v-if="log.kgMatchLogItems?.length" class="trace-grid">
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

          <div class="detail-dual" v-if="reasoningDisplaySteps.length || detail.graphContext?.length">
            <section class="detail-section detail-panel" v-if="reasoningDisplaySteps.length">
              <div class="detail-section-head">
                <div class="detail-section-title">
                  <h3>模型推理过程</h3>
                  <p>按阶段回放模型如何理解问题、定位数据、生成 SQL 并形成结果</p>
                </div>
              </div>
              <div class="reasoning-summary-grid">
                <article
                  v-for="card in reasoningOverviewCards"
                  :key="card.label"
                  class="reasoning-summary-card"
                >
                  <span>{{ card.label }}</span>
                  <strong>{{ card.value }}</strong>
                  <small>{{ card.hint }}</small>
                </article>
              </div>
              <div class="reasoning-card-list">
                <article
                  v-for="step in reasoningDisplaySteps"
                  :key="step.id"
                  class="reasoning-card"
                >
                  <div class="reasoning-card-head">
                    <div class="reasoning-card-index">{{ step.indexLabel }}</div>
                    <div class="reasoning-card-title">
                      <strong>{{ step.title }}</strong>
                      <small>{{ step.stage.hint }}</small>
                    </div>
                    <el-tag :type="step.stage.type" effect="plain">{{ step.stage.label }}</el-tag>
                  </div>
                  <p class="reasoning-card-main">{{ step.mainDetail }}</p>
                  <ul v-if="step.extraDetails.length" class="reasoning-detail-list">
                    <li v-for="(item, itemIndex) in step.extraDetails" :key="`${step.id}-${itemIndex}`">
                      {{ item }}
                    </li>
                  </ul>
                </article>
              </div>
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
                  <strong>{{ node.label || node.nodeKey || `节点 ${index + 1}` }}</strong>
                  <small>{{ node.nodeType || '-' }}</small>
                  <p>{{ node.content || '无内容' }}</p>
                </article>
              </div>
            </section>
          </div>

          <section class="detail-section detail-panel" v-if="detailHasResultPreview">
            <div class="detail-section-head">
              <div class="detail-section-title">
                <h3>结果预览</h3>
                <p>同时展示图表缩略图、字段映射和结果样例快照</p>
              </div>
            </div>
            <div class="result-preview-grid">
              <article class="context-card result-preview-card">
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

              <article class="context-card result-preview-card">
                <div class="context-card-head">
                  <span>结果概览</span>
                  <el-tag type="info" effect="plain">{{ detail.snapshotPreviewRows?.length || 0 }} 行样例</el-tag>
                </div>
                <div class="result-preview-metrics">
                  <div
                    v-for="item in resultPreviewMetrics"
                    :key="item.label"
                    class="result-preview-metric"
                  >
                    <span>{{ item.label }}</span>
                    <strong>{{ item.value }}</strong>
                    <small>{{ item.hint }}</small>
                  </div>
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
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
const rows = ref([])
const selectedIds = ref([])
const summary = ref({})
const governance = ref({})
const analytics = ref({
  trends: {},
  performance: {}
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

const detailChartPayload = computed(() => {
  const current = detail.value
  const snapshot = current?.chartSnapshot
  if (!current || !snapshot || typeof snapshot !== 'object') return null
  if (!Array.isArray(snapshot.data) || !snapshot.data.length) return null
  return {
    chartType: current.chartType || snapshot.chartType,
    chartSnapshot: snapshot
  }
})

const detailHasResultPreview = computed(() => Boolean(detailChartPayload.value || detail.value?.snapshotPreviewRows?.length))

const detailFieldMappingSummary = computed(() => summarizeFieldMapping(
  detail.value?.snapshotMetrics?.fieldMapping || detail.value?.chartSnapshot?.fieldMapping
))

const resultPreviewMetrics = computed(() => {
  const current = detail.value || {}
  const metrics = current.snapshotMetrics || {}
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

const reasoningOverviewCards = computed(() => {
  const current = detail.value || {}
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
      label: '生成策略',
      value: current.engine || current.modelCategory || '未记录解析引擎',
      hint: current.aiParseResultLabel || '未记录解析结果'
    },
    {
      label: '结果产物',
      value: chartTypeLabel(current.chartType),
      hint: current.sqlStatusLabel || '未记录 SQL 状态'
    }
  ]
})

const queryTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.queryVolume, 'totalCount'))
const riskTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.riskVolume, 'riskCount'))
const cacheTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.cacheVolume, 'hitCount'))
const slowTrendPoints = computed(() => asTrendPoints(analytics.value?.trends?.slowVolume, 'slowCount'))

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
    ElMessage.success(result.message || '复跑完成')
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
  --page-scale: 0.82;
  display: grid;
  gap: 14px;
  padding: 2px;
  color: #13213c;
  width: calc(100% / var(--page-scale));
  transform: scale(var(--page-scale));
  transform-origin: top left;
}

.history-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.history-hero h1 {
  margin: 0;
  font-size: 26px;
  line-height: 1.2;
  color: #101b36;
}

.history-hero p {
  margin: 8px 0 0;
  color: #66758e;
  font-size: 14px;
}

.hero-policy {
  display: inline-block;
  margin-top: 8px;
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.hero-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card,
.history-panel {
  min-width: 0;
  background: #fff;
  border: 1px solid #dfe7f3;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(44, 74, 124, 0.08);
}

.metric-card {
  display: grid;
  gap: 8px;
  min-height: 96px;
  padding: 16px 18px;
}

.metric-card--stacked {
  align-content: space-between;
}

.metric-card__main {
  display: grid;
  gap: 8px;
}

.metric-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.metric-card__meta small {
  margin: 0;
}

.metric-card span {
  color: #4b5b76;
  font-size: 14px;
  font-weight: 700;
}

.metric-card strong {
  color: #0f172a;
  font-size: 28px;
  line-height: 1.1;
}

.metric-card small {
  color: #74829a;
}

.metric-card--blue {
  background: linear-gradient(180deg, #ffffff 0%, #f5f9ff 100%);
}

.metric-card--orange {
  background: linear-gradient(180deg, #ffffff 0%, #fff7ed 100%);
}

.metric-card--green {
  background: linear-gradient(180deg, #ffffff 0%, #f0fdf4 100%);
}

.metric-card--red {
  background: linear-gradient(180deg, #ffffff 0%, #fff1f2 100%);
}

.history-panel {
  padding: 16px;
}

.analysis-grid,
.performance-summary-grid,
.performance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.analysis-card {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
  background: #ffffff;
}

.analysis-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.analysis-card-head h3 {
  margin: 0;
  color: #13213c;
  font-size: 14px;
}

.analysis-card-head p {
  margin: 6px 0 0;
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.hot-list {
  display: grid;
  gap: 10px;
}

.hot-list-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fbff;
}

.hot-list-main {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.hot-list-main strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.hot-list-main small {
  color: #74829a;
  font-size: 12px;
  word-break: break-word;
}

.hot-list-meta {
  display: grid;
  gap: 5px;
  text-align: right;
  flex-shrink: 0;
}

.hot-list-meta span {
  color: #74829a;
  font-size: 12px;
}

.hot-list-meta strong {
  color: #13213c;
  font-size: 13px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
}

.panel-head h2 {
  margin: 0;
  font-size: 17px;
  color: #13213c;
}

.panel-head p {
  margin: 7px 0 0;
  color: #66758e;
  font-size: 13px;
}

.panel-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.filter-actions {
  display: flex;
  gap: 8px;
}

.filter-actions :deep(.el-button) {
  flex: 1;
}

.history-table :deep(.el-table__row) {
  cursor: pointer;
}

.user-cell {
  display: grid;
  gap: 4px;
}

.user-cell strong {
  color: #13213c;
  font-size: 13px;
}

.user-cell small {
  color: #74829a;
  font-size: 12px;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.detail-wrap {
  display: grid;
  gap: 18px;
}

.detail-wrap--drawer {
  zoom: 1;
  width: 100%;
  transform: none;
}

.detail-overview {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.95fr);
  gap: 14px;
}

.detail-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.detail-panel--muted {
  background: linear-gradient(180deg, #ffffff 0%, #f7faff 100%);
}

.detail-overview-main {
  align-content: start;
  gap: 12px;
}

.detail-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-overview-main h2 {
  margin: 0;
  color: #0f172a;
  font-size: 24px;
  line-height: 1.35;
}

.detail-overview-main p {
  margin: 0;
  color: #52637f;
  line-height: 1.7;
}

.detail-overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.detail-overview-item {
  display: grid;
  gap: 5px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fbff;
}

.detail-overview-item span {
  color: #64748b;
  font-size: 12px;
}

.detail-overview-item strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.detail-overview-item small {
  color: #74829a;
  font-size: 12px;
  word-break: break-word;
}

.detail-section {
  display: grid;
  gap: 12px;
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
  font-size: 16px;
}

.detail-section-title p {
  margin: 0;
  color: #74829a;
  font-size: 13px;
  line-height: 1.6;
}

.detail-query-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: 12px;
}

.detail-subpanel {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.detail-subpanel--code {
  background: linear-gradient(180deg, #172033 0%, #0f172a 100%);
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
  gap: 10px;
}

.detail-grid--audit {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.context-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.context-card {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
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
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.context-card p {
  margin: 0;
  color: #52637f;
  font-size: 13px;
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
  padding: 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.detail-card span {
  color: #64748b;
  font-size: 12px;
}

.detail-card strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.detail-card small {
  color: #74829a;
  font-size: 12px;
  word-break: break-word;
}

.detail-card--blue {
  background: linear-gradient(180deg, #ffffff 0%, #f5f9ff 100%);
}

.detail-card--red {
  background: linear-gradient(180deg, #ffffff 0%, #fff3f2 100%);
}

.detail-card--amber {
  background: linear-gradient(180deg, #ffffff 0%, #fff8eb 100%);
}

.detail-card--green {
  background: linear-gradient(180deg, #ffffff 0%, #f1fbf5 100%);
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
  padding: 14px;
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
  background: #f8fbff;
  color: #13213c;
}

.audit-log-card {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
  background: #fbfdff;
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
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}

.audit-log-reason {
  margin: 0;
  color: #42546f;
  line-height: 1.7;
}

.audit-log-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.audit-log-grid > div {
  display: grid;
  gap: 6px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fbff;
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

.reasoning-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding-left: 18px;
  color: #334155;
}

.reasoning-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.reasoning-summary-card {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.reasoning-summary-card span {
  color: #64748b;
  font-size: 12px;
}

.reasoning-summary-card strong {
  color: #13213c;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.reasoning-summary-card small {
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.reasoning-card-list {
  display: grid;
  gap: 10px;
}

.reasoning-card {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.reasoning-card-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.reasoning-card-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  min-width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #e9f1ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.reasoning-card-title {
  display: grid;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.reasoning-card-title strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
}

.reasoning-card-title small {
  color: #74829a;
  line-height: 1.6;
}

.reasoning-card-main {
  margin: 0;
  color: #334155;
  line-height: 1.7;
}

.reasoning-detail-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: #52637f;
}

.reasoning-list li {
  display: grid;
  gap: 6px;
  padding: 12px 12px 12px 0;
  border-bottom: 1px dashed #d7e3f1;
}

.reasoning-list li:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.graph-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.graph-card {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #fbfdff;
}

.graph-card strong {
  color: #13213c;
  font-size: 14px;
}

.graph-card small {
  color: #64748b;
}

.graph-card p {
  margin: 0;
  color: #334155;
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
  gap: 14px;
}

.detail-table-wrap {
  overflow: hidden;
  border: 1px solid #e8eef8;
  border-radius: 8px;
}

.result-preview-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1.15fr) minmax(240px, 0.85fr);
  gap: 12px;
}

.result-preview-card {
  align-content: start;
}

.result-preview-chart-shell {
  min-height: 248px;
  padding: 8px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
}

.result-preview-chart {
  min-height: 232px;
}

.result-preview-metrics {
  display: grid;
  gap: 10px;
}

.result-preview-metric {
  display: grid;
  gap: 5px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fbff;
}

.result-preview-metric span {
  color: #64748b;
  font-size: 12px;
}

.result-preview-metric strong {
  color: #13213c;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.result-preview-metric small {
  color: #74829a;
  font-size: 12px;
  line-height: 1.6;
}

.trace-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #ffffff;
}

.trace-panel-head {
  display: grid;
  gap: 4px;
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

.trace-item {
  display: grid;
  gap: 6px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fbff;
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
  line-height: 1.6;
  word-break: break-word;
}

.trace-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.guard-summary {
  display: grid;
  gap: 8px;
}

.guard-detail-list {
  display: grid;
  gap: 6px;
}

.guard-detail-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fbff;
}

.guard-detail-item span {
  color: #64748b;
  font-size: 12px;
}

.guard-detail-item strong {
  text-align: right;
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
  padding: 18px 22px 12px;
  border-bottom: 1px solid #e8eef8;
  background: rgba(255, 255, 255, 0.96);
}

.history-detail-drawer :deep(.el-drawer__title) {
  color: #13213c;
  font-size: 19px;
  font-weight: 700;
}

.detail-scroll {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 22px 72px;
  box-sizing: border-box;
  background: linear-gradient(180deg, #f7faff 0%, #f3f7fc 100%);
}

.history-detail-drawer :deep(.el-table) {
  width: 100%;
}

.admin-chat-history :deep(.el-input__wrapper),
.admin-chat-history :deep(.el-select__wrapper),
.admin-chat-history :deep(.el-range-editor.el-input__wrapper) {
  min-height: 34px;
}

.admin-chat-history :deep(.el-button) {
  min-height: 32px;
  padding: 7px 12px;
}

.admin-chat-history :deep(.el-table) {
  font-size: 12px;
}

.admin-chat-history :deep(.el-table th),
.admin-chat-history :deep(.el-table td) {
  padding-top: 6px;
  padding-bottom: 6px;
}

@media (max-width: 1280px) {
  .metric-grid,
  .analysis-grid,
  .performance-summary-grid,
  .performance-grid,
  .filter-grid,
  .detail-overview,
  .detail-query-grid,
  .context-grid,
  .detail-dual,
  .detail-grid,
  .detail-grid--audit,
  .reasoning-summary-grid,
  .result-preview-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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
  .performance-summary-grid,
  .performance-grid,
  .filter-grid,
  .detail-overview,
  .detail-overview-grid,
  .detail-query-grid,
  .context-grid,
  .detail-dual,
  .detail-grid,
  .detail-grid--audit,
  .reasoning-summary-grid,
  .result-preview-grid,
  .audit-log-grid,
  .graph-list,
  .trace-grid {
    grid-template-columns: 1fr;
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
  }
}
</style>
