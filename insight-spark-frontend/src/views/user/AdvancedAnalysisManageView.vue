<template>
  <section class="advanced-manage-page">
    <header class="advanced-manage-header">
      <div class="advanced-manage-header__copy">
        <span class="module-kicker">用户端智能预测</span>
        <h1>预测与情景模拟</h1>
        <p>对话查询负责自然语言触发，这里集中管理预测方案、预警规则、预警事件和后续推送记录。</p>
      </div>
      <div class="advanced-manage-header__actions">
        <el-button :icon="ChatLineRound" @click="goChat">进入对话触发</el-button>
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </header>

    <div class="advanced-manage-metrics">
      <article
        v-for="card in summaryCards"
        :key="card.label"
        class="metric-card"
        :class="`is-${card.tone}`"
      >
        <span class="metric-card__icon">
          <el-icon><component :is="card.icon" /></el-icon>
        </span>
        <div class="metric-card__body">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.description }}</small>
        </div>
      </article>
    </div>

    <el-tabs v-model="activeTab" class="advanced-manage-tabs">
      <el-tab-pane name="rules">
        <template #label>
          <span class="tab-label"><span>预警规则</span><em>{{ rules.length }}</em></span>
        </template>
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>预警规则管理</h2>
              <p>支持编辑、启停、删除和手动检测；自动轮询由后端离线 Agent 执行。</p>
            </div>
            <div class="batch-action-bar">
              <span>已选择 {{ selectedRuleIds.length }} 条</span>
              <el-button
                size="small"
                :disabled="!selectedRuleIds.length"
                :loading="batchRuleOperating"
                @click="batchDisableRules"
              >
                批量停用
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="!selectedRuleIds.length"
                :loading="batchRuleOperating"
                @click="batchRemoveRules"
              >
                批量删除
              </el-button>
            </div>
          </div>
          <el-table
            ref="rulesTableRef"
            class="manage-table"
            :data="paginatedRules"
            row-key="id"
            v-loading="loading"
            empty-text="暂无预警规则"
            @selection-change="handleRuleSelectionChange"
          >
            <el-table-column type="selection" width="46" reserve-selection />
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="规则" min-width="220">
              <template #default="{ row }">
                <div class="rule-title">{{ formatRuleTitle(row) }}</div>
                <div class="rule-meta">{{ row.tableName }} / {{ row.filterExpression || '无过滤条件' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="周期" width="120">
              <template #default="{ row }">{{ cycleLabel(row.detectionCycle) }}</template>
            </el-table-column>
            <el-table-column label="调度状态" min-width="210">
              <template #default="{ row }">
                <div class="schedule-meta">
                  <span>上次检测：{{ formatScheduleTime(row.lastCheckedAt) }}</span>
                  <span>下次检测：{{ nextDetectionText(row) }}</span>
                  <span>上次触发：{{ formatTriggerTime(row.lastTriggeredAt) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="渠道" width="140">
              <template #default="{ row }">{{ channelLabel(row.channels) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">
                  {{ row.status === 'ACTIVE' ? '已启用' : row.status === 'DISABLED' ? '已停用' : row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="editRule(row)">编辑</el-button>
                <el-button size="small" text type="success" @click="runDetection(row)">检测</el-button>
                <el-button size="small" text @click="toggleRule(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
                <el-button size="small" text type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-pagination">
            <span>共 {{ rules.length }} 条</span>
            <el-pagination
              v-model:current-page="tablePagination.rules.page"
              v-model:page-size="tablePagination.rules.pageSize"
              background
              :page-sizes="pageSizeOptions"
              :total="rules.length"
              layout="sizes, prev, pager, next, jumper"
              @size-change="handlePageSizeChange('rules')"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane name="events">
        <template #label>
          <span class="tab-label"><span>预警事件</span><em>{{ events.length }}</em></span>
        </template>
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>预警事件</h2>
              <p>展示阈值和 Z-Score 检测生成的事件，支持确认、关闭、重开和处理备注。</p>
            </div>
          </div>
          <el-table class="manage-table" :data="paginatedEvents" v-loading="loading" empty-text="暂无预警事件">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="ruleId" label="规则ID" width="90" />
            <el-table-column prop="bucketName" label="时间桶" width="130" />
            <el-table-column label="实际值" width="120">
              <template #default="{ row }">{{ formatNumber(row.actualValue) }}</template>
            </el-table-column>
            <el-table-column label="触发原因" min-width="320">
              <template #default="{ row }">{{ row.reason || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="eventStatusTagType(row.status)" effect="light">
                  {{ eventStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处理备注" min-width="180">
              <template #default="{ row }">
                <span class="event-note" :title="row.handleNote || ''">{{ row.handleNote || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180" />
            <el-table-column label="操作" width="320" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openEventSnapshot(row)">快照</el-button>
                <el-button size="small" text type="primary" @click="goChatWithAlertEvent(row)">回到对话</el-button>
                <el-button
                  v-if="row.status === 'OPEN'"
                  size="small"
                  text
                  @click="openEventStatusDialog(row, 'ACK')"
                >
                  确认
                </el-button>
                <el-button
                  v-if="row.status !== 'CLOSED'"
                  size="small"
                  text
                  type="success"
                  @click="openEventStatusDialog(row, 'CLOSED')"
                >
                  关闭
                </el-button>
                <el-button
                  v-if="row.status === 'CLOSED'"
                  size="small"
                  text
                  @click="openEventStatusDialog(row, 'OPEN')"
                >
                  重开
                </el-button>
                <el-button size="small" text @click="openEventStatusDialog(row, row.status || 'ACK')">备注</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-pagination">
            <span>共 {{ events.length }} 条</span>
            <el-pagination
              v-model:current-page="tablePagination.events.page"
              v-model:page-size="tablePagination.events.pageSize"
              background
              :page-sizes="pageSizeOptions"
              :total="events.length"
              layout="sizes, prev, pager, next, jumper"
              @size-change="handlePageSizeChange('events')"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane name="push">
        <template #label>
          <span class="tab-label"><span>推送记录</span><em>{{ pushLogs.length }}</em></span>
        </template>
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>预警推送记录</h2>
              <p>记录邮件/钉钉推送尝试、失败原因和重试结果；外部渠道未配置时不会影响预警事件生成。</p>
            </div>
            <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
          </div>
          <div class="push-config-grid">
            <article
              v-for="item in pushConfigList"
              :key="item.channel"
              class="push-config-card"
              :class="{ 'is-available': item.available }"
            >
              <span>{{ pushChannelLabel(item.channel) }}</span>
              <strong>{{ item.available ? '可用' : '未配置' }}</strong>
              <small>{{ item.message }}</small>
              <em v-if="item.target">{{ item.target }}</em>
            </article>
          </div>
          <el-table class="manage-table" :data="paginatedPushLogs" v-loading="loading" empty-text="暂无推送记录">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="eventId" label="事件ID" width="90" />
            <el-table-column prop="ruleId" label="规则ID" width="90" />
            <el-table-column label="渠道" width="100">
              <template #default="{ row }">{{ pushChannelLabel(row.channel) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="pushStatusTagType(row.status)" effect="light">
                  {{ pushStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="尝试" width="90">
              <template #default="{ row }">{{ row.attemptCount || 0 }} 次</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="220" />
            <el-table-column label="失败原因" min-width="260">
              <template #default="{ row }">
                <span class="event-note" :title="row.errorMessage || ''">{{ row.errorMessage || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="lastAttemptAt" label="最近尝试" width="180" />
            <el-table-column prop="nextRetryAt" label="建议重试" width="180" />
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  text
                  type="primary"
                  :loading="restoringPushId === row.id"
                  @click="goChatWithPushLog(row)"
                >
                  回到对话
                </el-button>
                <el-button
                  size="small"
                  text
                  type="primary"
                  :loading="retryingPushId === row.id"
                  @click="retryPush(row)"
                >
                  重试
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-pagination">
            <span>共 {{ pushLogs.length }} 条</span>
            <el-pagination
              v-model:current-page="tablePagination.push.page"
              v-model:page-size="tablePagination.push.pageSize"
              background
              :page-sizes="pageSizeOptions"
              :total="pushLogs.length"
              layout="sizes, prev, pager, next, jumper"
              @size-change="handlePageSizeChange('push')"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane name="plans">
        <template #label>
          <span class="tab-label"><span>预测/推演方案</span><em>{{ plans.length }}</em></span>
        </template>
        <section class="manage-panel">
          <div class="panel-head">
            <div>
              <h2>方案资产管理</h2>
              <p>集中查看已保存的预测和 What-if 方案，支持详情查看、历史复算和删除。</p>
            </div>
            <div class="batch-action-bar">
              <span>已选择 {{ selectedPlanIds.length }} 条</span>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="!selectedPlanIds.length"
                :loading="batchPlanOperating"
                @click="batchRemovePlans"
              >
                批量删除
              </el-button>
            </div>
          </div>
          <el-table
            ref="plansTableRef"
            class="manage-table"
            :data="paginatedPlans"
            row-key="id"
            v-loading="loading"
            empty-text="暂无预测/推演方案"
            @selection-change="handlePlanSelectionChange"
          >
            <el-table-column type="selection" width="46" reserve-selection />
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-tag effect="light" :type="row.planType === 'forecast' ? 'primary' : 'success'">
                  {{ planTypeLabel(row.planType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方案" min-width="240">
              <template #default="{ row }">
                <div class="rule-title">{{ row.planName || '未命名方案' }}</div>
                <div class="rule-meta">{{ row.metricLabel || '自动推断指标' }} / {{ row.timeRangeLabel || '自定义周期' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="tableName" label="数据源" min-width="160" />
            <el-table-column label="版本" width="90">
              <template #default="{ row }">v{{ row.versionNo || 1 }}</template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="180" />
            <el-table-column label="操作" width="410" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openPlan(row)">详情</el-button>
                <el-button size="small" text @click="renamePlan(row)">重命名</el-button>
                <el-button size="small" text type="warning" @click="openPlanVersions(row)">版本</el-button>
                <el-button
                  size="small"
                  text
                  type="success"
                  :disabled="!canPinPlan(row)"
                  @click="openPinPlanDialog(row)"
                >
                  钉入看板
                </el-button>
                <el-button size="small" text type="success" @click="recalculatePlan(row)">复算</el-button>
                <el-button size="small" text type="danger" @click="removePlan(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-pagination">
            <span>共 {{ plans.length }} 条</span>
            <el-pagination
              v-model:current-page="tablePagination.plans.page"
              v-model:page-size="tablePagination.plans.pageSize"
              background
              :page-sizes="pageSizeOptions"
              :total="plans.length"
              layout="sizes, prev, pager, next, jumper"
              @size-change="handlePageSizeChange('plans')"
            />
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="planDetailVisible"
      :title="selectedPlan?.planName || '方案详情'"
      width="920px"
      destroy-on-close
    >
      <div v-if="selectedPlan" class="plan-detail">
        <div class="plan-detail__meta">
          <span>{{ planTypeLabel(selectedPlan.planType) }}</span>
          <span>v{{ selectedPlan.versionNo || 1 }}</span>
          <span>{{ selectedPlan.updatedAt || selectedPlan.createdAt || '-' }}</span>
        </div>
        <div v-if="formatPlanFieldMappingRows(selectedPlan).length" class="field-mapping-panel">
          <div class="field-mapping-panel__head">
            <span>字段映射快照</span>
            <strong>用户确认口径</strong>
          </div>
          <div class="field-mapping-panel__grid">
            <div
              v-for="item in formatPlanFieldMappingRows(selectedPlan)"
              :key="item.label"
              class="field-mapping-panel__item"
            >
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
        </div>
        <AdvancedAnalysisCard
          v-if="selectedPlanAnalysis"
          :analysis="selectedPlanAnalysis"
          :show-save-action="false"
          :show-pin-action="false"
          :explain-loading="advancedAnalysisExplainingId === selectedPlanAnalysis.id"
          @recalculate="recalculateSelectedPlan"
          @explain="explainPlanAnalysis"
        />
      </div>
      <template #footer>
        <el-button @click="planDetailVisible = false">关闭</el-button>
        <el-button type="primary" :loading="planRecalculating" @click="recalculateSelectedPlan">重新计算</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="planVersionVisible"
      :title="versionDialogTitle"
      width="980px"
      destroy-on-close
    >
      <div v-if="selectedPlan" class="plan-version-panel">
        <el-alert
          v-if="versionCompareSummary.length"
          :closable="false"
          type="info"
          show-icon
          :title="versionCompareTitle"
          class="version-alert"
        />
        <div class="plan-version-panel__toolbar">
          <el-select v-model="versionCompareForm.leftVersion" placeholder="左侧版本" style="width: 180px">
            <el-option
              v-for="item in planVersions"
              :key="`left-${item.versionNo}`"
              :label="`v${item.versionNo} · ${item.planName || '未命名'}`"
              :value="item.versionNo"
            />
          </el-select>
          <el-select v-model="versionCompareForm.rightVersion" placeholder="右侧版本" style="width: 180px">
            <el-option
              v-for="item in planVersions"
              :key="`right-${item.versionNo}`"
              :label="`v${item.versionNo} · ${item.planName || '未命名'}`"
              :value="item.versionNo"
            />
          </el-select>
          <el-button type="primary" :loading="versionComparing" @click="compareSelectedPlanVersions">对比</el-button>
          <el-button @click="loadPlanVersions(selectedPlan, true)">刷新版本</el-button>
        </div>
        <el-table :data="planVersions" border empty-text="暂无版本记录">
          <el-table-column prop="versionNo" label="版本" width="90" />
          <el-table-column prop="planName" label="名称" min-width="220" />
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" text @click="applyVersionToCompare(row.versionNo, 'left')">设为左侧</el-button>
              <el-button size="small" text @click="applyVersionToCompare(row.versionNo, 'right')">设为右侧</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="versionCompareResult" class="plan-version-compare">
          <div class="plan-version-compare__col">
            <h4>左侧版本</h4>
            <div v-if="formatPlanFieldMappingRows(versionCompareResult.left).length" class="field-mapping-panel field-mapping-panel--compact">
              <div class="field-mapping-panel__grid">
                <div
                  v-for="item in formatPlanFieldMappingRows(versionCompareResult.left)"
                  :key="item.label"
                  class="field-mapping-panel__item"
                >
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </div>
              </div>
            </div>
            <AdvancedAnalysisCard
              v-if="leftVersionAnalysis"
              :analysis="leftVersionAnalysis"
              :show-save-action="false"
              :show-pin-action="false"
              :explain-loading="advancedAnalysisExplainingId === leftVersionAnalysis.id"
              @explain="explainPlanAnalysis"
            />
          </div>
          <div class="plan-version-compare__col">
            <h4>右侧版本</h4>
            <div v-if="formatPlanFieldMappingRows(versionCompareResult.right).length" class="field-mapping-panel field-mapping-panel--compact">
              <div class="field-mapping-panel__grid">
                <div
                  v-for="item in formatPlanFieldMappingRows(versionCompareResult.right)"
                  :key="item.label"
                  class="field-mapping-panel__item"
                >
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </div>
              </div>
            </div>
            <AdvancedAnalysisCard
              v-if="rightVersionAnalysis"
              :analysis="rightVersionAnalysis"
              :show-save-action="false"
              :show-pin-action="false"
              :explain-loading="advancedAnalysisExplainingId === rightVersionAnalysis.id"
              @explain="explainPlanAnalysis"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="planVersionVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pinPlanVisible" title="钉入我的看板" width="520px" destroy-on-close>
      <p class="pin-dialog-hint">仅显示未发布的可编辑看板；已发布看板不可钉入，请先另存为副本。</p>
      <el-form label-position="top">
        <el-form-item label="预测图表">
          <el-input :model-value="pinPlanTarget?.planName || '-'" disabled />
        </el-form-item>
        <el-form-item label="目标看板">
          <el-select v-model="pinDashboardId" class="full-width" placeholder="请选择看板">
            <el-option
              v-for="dashboard in dashboardOptions"
              :key="dashboard.id"
              :label="formatPinDashboardLabel(dashboard)"
              :value="dashboard.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pinPlanVisible = false">取消</el-button>
        <el-button type="primary" :loading="pinningPlan" @click="pinPlanToDashboard">确认钉入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editorVisible" title="编辑预警规则" width="640px" destroy-on-close>
      <el-form label-position="top" class="rule-editor-form">
        <div class="form-grid">
          <el-form-item label="时间字段">
            <el-select v-model="editorForm.timeField" filterable class="full-width">
              <el-option v-for="field in editorMeta.timeFields" :key="field.columnName" :label="fieldLabel(field)" :value="field.columnName" />
            </el-select>
          </el-form-item>
          <el-form-item label="指标字段">
            <el-select v-model="editorForm.metricField" filterable class="full-width">
              <el-option v-for="field in editorMeta.numericFields" :key="field.columnName" :label="fieldLabel(field)" :value="field.columnName" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="过滤条件（可选）">
          <el-input v-model.trim="editorForm.filterExpression" placeholder="例如：region = '华东'" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="聚合粒度">
            <el-select v-model="editorForm.granularity">
              <el-option label="按日" value="day" />
              <el-option label="按周" value="week" />
              <el-option label="按月" value="month" />
              <el-option label="按季度" value="quarter" />
              <el-option label="按年" value="year" />
            </el-select>
          </el-form-item>
          <el-form-item label="检测周期">
            <el-select v-model="editorForm.detectionCycle">
              <el-option label="每小时" value="hourly" />
              <el-option label="每日" value="daily" />
              <el-option label="每周" value="weekly" />
              <el-option label="每月" value="monthly" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="判断条件">
            <el-select v-model="editorForm.operator">
              <el-option label="低于阈值" value="lt" />
              <el-option label="高于阈值" value="gt" />
              <el-option label="Z-Score 异常波动" value="zscore" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值">
            <el-input-number v-model="editorForm.threshold" :min="0" :disabled="editorForm.operator === 'zscore'" />
          </el-form-item>
        </div>
        <el-form-item label="通知渠道">
          <el-checkbox-group v-model="editorForm.channels">
            <el-checkbox label="email">邮件</el-checkbox>
            <el-checkbox label="dingtalk">钉钉</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEditor">保存修改</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="eventStatusVisible" :title="eventStatusDialogTitle" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="预警事件">
          <el-input :model-value="eventStatusTarget ? formatEventTitle(eventStatusTarget) : '-'" disabled />
        </el-form-item>
        <el-form-item label="处理状态">
          <el-select v-model="eventStatusForm.status" class="full-width">
            <el-option label="待处理" value="OPEN" />
            <el-option label="已确认" value="ACK" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input
            v-model.trim="eventStatusForm.handleNote"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="记录确认结论、处置动作、关闭原因等"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="eventStatusVisible = false">取消</el-button>
        <el-button type="primary" :loading="eventStatusSaving" @click="submitEventStatus">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="eventSnapshotVisible"
      title="预警图表快照"
      width="860px"
      destroy-on-close
      class="alert-snapshot-dialog"
    >
      <div v-if="eventSnapshotTarget" class="alert-snapshot">
        <div class="alert-snapshot__summary">
          <div>
            <span>触发时间桶</span>
            <strong>{{ eventSnapshotTarget.bucketName || '-' }}</strong>
          </div>
          <div>
            <span>实际值</span>
            <strong>{{ formatNumber(eventSnapshotTarget.actualValue) }}</strong>
          </div>
          <div>
            <span>阈值/基线</span>
            <strong>{{ snapshotThresholdText }}</strong>
          </div>
          <div>
            <span>Z-Score</span>
            <strong>{{ formatNumber(eventSnapshotTarget.zScore) }}</strong>
          </div>
        </div>
        <div ref="eventSnapshotChartRef" class="alert-snapshot__chart"></div>
        <div class="alert-snapshot__reason">
          {{ eventSnapshotTarget.reason || '暂无触发原因说明' }}
        </div>
        <section class="alert-explain-panel">
          <div class="alert-explain-panel__head">
            <div>
              <strong>预警解释与处理建议</strong>
              <span>{{ alertExplanationSourceText }}</span>
            </div>
            <el-button
              size="small"
              type="primary"
              :loading="eventExplaining"
              @click="generateEventExplanation"
            >
              生成 AI 解释
            </el-button>
          </div>
          <div v-if="alertExplanation" class="alert-explain-panel__content">
            <div>
              <h4>触发解读</h4>
              <ul>
                <li v-for="item in alertExplanationCalculation" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <h4>建议动作</h4>
              <ul>
                <li v-for="item in alertExplanationSuggestions" :key="item">{{ item }}</li>
              </ul>
            </div>
          </div>
          <el-empty v-else description="暂无解释，可点击生成 AI 解释" :image-size="72" />
          <el-input
            v-model.trim="eventExplanationNote"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="可补充人工判断、复核结论、跟进动作等解释备注"
          />
        </section>
      </div>
      <template #footer>
        <el-button :loading="eventExplanationSaving" @click="saveEventExplanationNote">保存解释备注</el-button>
        <el-button @click="eventSnapshotVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Bell,
  ChatLineRound,
  DataAnalysis,
  Refresh,
  Timer,
  TrendCharts,
  WarningFilled
} from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import AdvancedAnalysisCard from '../../components/AdvancedAnalysisCard.vue'
import {
  batchDeleteAdvancedAnalysisPlans,
  batchDeleteAdvancedAlertRules,
  batchUpdateAdvancedAlertRuleStatus,
  compareAdvancedAnalysisPlanVersions,
  compareLatestAdvancedAnalysisPlanVersions,
  deleteAdvancedAnalysisPlan,
  deleteAdvancedAlertRule,
  explainAdvancedAlertEvent,
  explainAdvancedAnalysisResult,
  fetchAdvancedAlertPushConfig,
  fetchAdvancedAnalysisFieldMeta,
  getAdvancedAnalysisPlan,
  getAdvancedAlertEvent,
  getAdvancedAlertRule,
  listAdvancedAnalysisPlans,
  listAdvancedAlertEvents,
  listAdvancedAlertPushLogs,
  listAdvancedAlertRules,
  listAdvancedAnalysisPlanVersions,
  recalculateAdvancedAnalysisPlan,
  renameAdvancedAnalysisPlan,
  retryAdvancedAlertPush,
  runAdvancedAlertDetection,
  updateAdvancedAlertEventStatus,
  updateAdvancedAlertRule,
  updateAdvancedAlertRuleStatus
} from '../../api/advancedAnalysis'
import {
  listPinTargetDashboards,
  pinChartToDashboard
} from '../../api/dashboard'

const workbench = inject('workbench', null)

const activeTab = ref('rules')
const loading = ref(false)
const saving = ref(false)
const rules = ref([])
const events = ref([])
const pushLogs = ref([])
const pushConfig = ref({})
const plans = ref([])
const rulesTableRef = ref(null)
const selectedRules = ref([])
const batchRuleOperating = ref(false)
const plansTableRef = ref(null)
const selectedPlans = ref([])
const batchPlanOperating = ref(false)
const pageSizeOptions = [10, 20, 50, 100]
const tablePagination = reactive({
  rules: {
    page: 1,
    pageSize: 10
  },
  events: {
    page: 1,
    pageSize: 10
  },
  push: {
    page: 1,
    pageSize: 10
  },
  plans: {
    page: 1,
    pageSize: 10
  }
})
const selectedPlan = ref(null)
const planDetailVisible = ref(false)
const planVersionVisible = ref(false)
const planRecalculating = ref(false)
const advancedAnalysisExplainingId = ref('')
const versionComparing = ref(false)
const planVersions = ref([])
const versionCompareResult = ref(null)
const pinPlanVisible = ref(false)
const pinningPlan = ref(false)
const pinPlanTarget = ref(null)
const dashboardOptions = ref([])
const pinDashboardId = ref('')
const editorVisible = ref(false)
const editorMeta = ref({ timeFields: [], numericFields: [] })
const eventStatusVisible = ref(false)
const eventStatusSaving = ref(false)
const eventStatusTarget = ref(null)
const eventSnapshotVisible = ref(false)
const eventSnapshotTarget = ref(null)
const eventSnapshotChartRef = ref(null)
const eventExplaining = ref(false)
const eventExplanationSaving = ref(false)
const eventExplanationNote = ref('')
let eventSnapshotChart = null
const retryingPushId = ref('')
const restoringPushId = ref('')
const versionCompareForm = reactive({
  leftVersion: '',
  rightVersion: ''
})
const eventStatusForm = reactive({
  status: 'ACK',
  handleNote: ''
})
const editorForm = ref({
  id: '',
  tableName: '',
  timeField: '',
  metricField: '',
  filterExpression: '',
  granularity: 'day',
  operator: 'lt',
  threshold: 100000,
  detectionCycle: 'daily',
  channels: ['email', 'dingtalk'],
  status: 'ACTIVE'
})

const activeRuleCount = computed(() => rules.value.filter(item => item.status === 'ACTIVE').length)
const selectedRuleIds = computed(() => selectedRules.value
  .map(item => Number(item.id))
  .filter(id => Number.isFinite(id) && id > 0))
const selectedPlanIds = computed(() => selectedPlans.value
  .map(item => Number(item.id))
  .filter(id => Number.isFinite(id) && id > 0))

const clampTablePage = (key, total) => {
  const pager = tablePagination[key]
  if (!pager) return
  const safeTotal = Math.max(0, Number(total) || 0)
  const safePageSize = Math.max(1, Number(pager.pageSize) || 10)
  const maxPage = Math.max(1, Math.ceil(safeTotal / safePageSize))
  if (pager.page > maxPage) {
    pager.page = maxPage
  } else if (pager.page < 1) {
    pager.page = 1
  }
}

const paginateRows = (rows, key) => {
  const source = Array.isArray(rows) ? rows : []
  const pager = tablePagination[key] || { page: 1, pageSize: 10 }
  const page = Math.max(1, Number(pager.page) || 1)
  const pageSize = Math.max(1, Number(pager.pageSize) || 10)
  const start = (page - 1) * pageSize
  return source.slice(start, start + pageSize)
}

const handlePageSizeChange = (key) => {
  if (tablePagination[key]) {
    tablePagination[key].page = 1
  }
}

const paginatedRules = computed(() => paginateRows(rules.value, 'rules'))
const paginatedEvents = computed(() => paginateRows(events.value, 'events'))
const paginatedPushLogs = computed(() => paginateRows(pushLogs.value, 'push'))
const paginatedPlans = computed(() => paginateRows(plans.value, 'plans'))

const summaryCards = computed(() => [
  {
    label: '预警规则',
    value: rules.value.length,
    description: '已保存规则总数',
    icon: DataAnalysis,
    tone: 'blue'
  },
  {
    label: '启用规则',
    value: activeRuleCount.value,
    description: '离线 Agent 轮询范围',
    icon: Timer,
    tone: 'green'
  },
  {
    label: '预警事件',
    value: events.value.length,
    description: '最近事件记录',
    icon: WarningFilled,
    tone: 'orange'
  },
  {
    label: '推送记录',
    value: pushLogs.value.length,
    description: '邮件/钉钉尝试记录',
    icon: Bell,
    tone: 'cyan'
  },
  {
    label: '方案资产',
    value: plans.value.length,
    description: '预测/推演保存记录',
    icon: TrendCharts,
    tone: 'purple'
  }
])

const alertExplanation = computed(() => {
  const value = eventSnapshotTarget.value?.llmExplanation
  return value && typeof value === 'object' && Object.keys(value).length ? value : null
})

const alertExplanationCalculation = computed(() => {
  const rows = alertExplanation.value?.calculation
  return Array.isArray(rows) && rows.length ? rows : ['当前暂无可展示的触发解读。']
})

const alertExplanationSuggestions = computed(() => {
  const rows = alertExplanation.value?.suggestions
  return Array.isArray(rows) && rows.length ? rows : ['请先核对触发时间桶原始数据、过滤口径和业务背景。']
})

const alertExplanationSourceText = computed(() => {
  if (!alertExplanation.value) return '尚未生成解释'
  const source = alertExplanation.value.sourceLabel || (alertExplanation.value.source === 'llm' ? 'AI 解释' : '规则解释')
  const updatedAt = eventSnapshotTarget.value?.explanationUpdatedAt
  return updatedAt ? `${source} / ${formatScheduleTime(updatedAt)}` : source
})

const pushConfigList = computed(() => {
  const config = pushConfig.value || {}
  return ['email', 'dingtalk'].map(channel => ({
    channel,
    available: Boolean(config[channel]?.available),
    message: config[channel]?.message || '未获取配置状态',
    target: config[channel]?.target || ''
  }))
})

const selectedPlanAnalysis = computed(() => normalizePlanAnalysis(selectedPlan.value))

const versionDialogTitle = computed(() => `${selectedPlan.value?.planName || '方案'} 版本历史`)

const versionCompareSummary = computed(() => {
  const summary = versionCompareResult.value?.summary
  return Array.isArray(summary) ? summary : []
})

const versionCompareTitle = computed(() => {
  const summaryText = versionCompareSummary.value
    .map(item => `${item.label}: ${item.value}`)
    .join(' · ')
  if (summaryText) {
    return summaryText
  }
  const leftVersion = versionCompareResult.value?.left?.versionNo
  const rightVersion = versionCompareResult.value?.right?.versionNo
  if (leftVersion && rightVersion) {
    return `版本对比：v${leftVersion} vs v${rightVersion}`
  }
  return '版本对比结果'
})

const leftVersionAnalysis = computed(() => snapshotToAnalysis(versionCompareResult.value?.left))
const rightVersionAnalysis = computed(() => snapshotToAnalysis(versionCompareResult.value?.right))

const eventStatusDialogTitle = computed(() => {
  const label = eventStatusLabel(eventStatusForm.status)
  return label ? `处理预警事件 - ${label}` : '处理预警事件'
})

const snapshotThresholdText = computed(() => {
  const snapshot = eventSnapshotTarget.value?.chartSnapshot || {}
  const threshold = snapshot.threshold ?? eventSnapshotTarget.value?.threshold
  const baseline = snapshot.baseline ?? eventSnapshotTarget.value?.baselineValue
  const parts = []
  if (threshold !== undefined && threshold !== null && threshold !== '') {
    parts.push(`阈值 ${formatNumber(threshold)}`)
  }
  if (baseline !== undefined && baseline !== null && baseline !== '') {
    parts.push(`基线 ${formatNumber(baseline)}`)
  }
  return parts.length ? parts.join(' / ') : '-'
})

const fieldLabel = (field) => {
  const displayName = String(field?.displayName || field?.businessName || '').trim()
  const columnName = String(field?.columnName || '').trim()
  return displayName && columnName && displayName !== columnName ? `${displayName}（${columnName}）` : displayName || columnName || '未命名字段'
}

const formatNumber = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(2)}万`
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

const cycleLabel = (cycle) => ({ hourly: '每小时', daily: '每日', weekly: '每周', monthly: '每月' }[cycle] || '每日')

const formatScheduleTime = (value) => {
  const text = String(value || '').trim()
  if (!text) return '尚未检测'
  return text.replace('T', ' ').slice(0, 19)
}

const formatTriggerTime = (value) => {
  const text = String(value || '').trim()
  if (!text) return '暂无触发'
  return formatScheduleTime(text)
}

const nextDetectionText = (rule = {}) => {
  const checkedAt = String(rule.lastCheckedAt || '').trim()
  if (!checkedAt) return '待 Agent 首次检测'
  const date = new Date(checkedAt.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return '按周期待检测'
  const cycle = String(rule.detectionCycle || 'daily')
  if (cycle === 'hourly') date.setHours(date.getHours() + 1)
  else if (cycle === 'weekly') date.setDate(date.getDate() + 7)
  else if (cycle === 'monthly') date.setMonth(date.getMonth() + 1)
  else date.setDate(date.getDate() + 1)
  const now = new Date()
  if (date.getTime() <= now.getTime()) return '已到期，等待 Agent 轮询'
  return date.toLocaleString('zh-CN', { hour12: false })
}

const eventStatusLabel = (status) => {
  const value = String(status || 'OPEN').toUpperCase()
  if (value === 'ACK') return '已确认'
  if (value === 'CLOSED') return '已关闭'
  return '待处理'
}

const eventStatusTagType = (status) => {
  const value = String(status || 'OPEN').toUpperCase()
  if (value === 'ACK') return 'warning'
  if (value === 'CLOSED') return 'success'
  return 'danger'
}

const pushChannelLabel = (channel) => {
  const value = String(channel || '').toLowerCase()
  if (value === 'dingtalk') return '钉钉'
  if (value === 'email') return '邮件'
  return value || '-'
}

const pushStatusLabel = (status) => {
  const value = String(status || 'PENDING').toUpperCase()
  if (value === 'SUCCESS') return '成功'
  if (value === 'FAILED') return '失败'
  return '待推送'
}

const pushStatusTagType = (status) => {
  const value = String(status || 'PENDING').toUpperCase()
  if (value === 'SUCCESS') return 'success'
  if (value === 'FAILED') return 'danger'
  return 'warning'
}

const formatEventTitle = (event = {}) => {
  return `#${event.id || '-'} / 规则#${event.ruleId || '-'} / ${event.bucketName || '-'} / ${formatNumber(event.actualValue)}`
}

const formatAxisValue = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(1)}万`
  return `${number}`
}

const buildSnapshotOption = (event = {}) => {
  const snapshot = event.chartSnapshot || {}
  const rows = Array.isArray(snapshot.data) && snapshot.data.length
    ? snapshot.data
    : [{ name: event.bucketName || '-', value: Number(event.actualValue || 0), triggered: true }]
  const threshold = snapshot.threshold ?? event.threshold
  const baseline = snapshot.baseline ?? event.baselineValue
  const markLineData = []
  if (threshold !== undefined && threshold !== null && threshold !== '') {
    markLineData.push({ name: '阈值', yAxis: Number(threshold), lineStyle: { color: '#ef4444', type: 'dashed' } })
  }
  if (baseline !== undefined && baseline !== null && baseline !== '') {
    markLineData.push({ name: '历史基线', yAxis: Number(baseline), lineStyle: { color: '#64748b', type: 'dotted' } })
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 4, data: ['检测值'] },
    grid: { left: 54, right: 28, top: 48, bottom: 42, containLabel: true },
    xAxis: {
      type: 'category',
      data: rows.map(item => item.name),
      axisLabel: { hideOverlap: true }
    },
    yAxis: { type: 'value', axisLabel: { formatter: formatAxisValue } },
    series: [
      {
        name: '检测值',
        type: 'line',
        smooth: true,
        data: rows.map(item => ({
          value: Number(item.value || 0),
          itemStyle: item.triggered ? { color: '#ef4444' } : { color: '#2563eb' },
          symbolSize: item.triggered ? 10 : 6
        })),
        lineStyle: { color: '#2563eb', width: 2 },
        areaStyle: { color: 'rgba(37, 99, 235, 0.08)' },
        markLine: markLineData.length ? { symbol: 'none', data: markLineData } : undefined
      }
    ]
  }
}

const channelLabel = (channels = []) => {
  const values = Array.isArray(channels) ? channels : [channels]
  const labels = values.map(item => item === 'email' ? '邮件' : item === 'dingtalk' ? '钉钉' : '').filter(Boolean)
  return labels.length ? [...new Set(labels)].join(' + ') : '邮件 + 钉钉'
}

const formatRuleTitle = (rule) => {
  const operatorText = { lt: '低于', gt: '高于', zscore: '异常波动' }[rule.operator] || '触发'
  const threshold = rule.operator === 'zscore' ? 'Z-Score' : formatNumber(rule.threshold)
  return `${rule.metricField || '指标'} ${operatorText} ${threshold}`
}

const planTypeLabel = (type) => type === 'forecast' ? '时序预测' : type === 'whatIf' ? 'What-if 推演' : '方案'

const compactText = (value) => String(value ?? '').trim()

const formatVariableMapping = (variables = []) => {
  const rows = Array.isArray(variables) ? variables : []
  const text = rows
    .map(item => {
      const name = compactText(item?.name || item?.field)
      const field = compactText(item?.field)
      const mode = compactText(item?.mode)
      const change = item?.change ?? ''
      const range = [
        item?.min !== undefined && item?.min !== null ? `min=${item.min}` : '',
        item?.max !== undefined && item?.max !== null ? `max=${item.max}` : ''
      ].filter(Boolean).join(', ')
      return [name && field && name !== field ? `${name}(${field})` : name || field, mode, change !== '' ? `变化=${change}` : '', range].filter(Boolean).join(' / ')
    })
    .filter(Boolean)
    .join('；')
  return text || ''
}

const formatPlanFieldMappingRows = (plan = {}) => {
  const mapping = plan?.fieldMapping || {}
  const request = plan?.request || {}
  const result = plan?.result || {}
  const params = result?.params || {}
  const type = compactText(mapping.mappingType || plan.planType)
  const rows = [
    { label: '数据源', value: compactText(mapping.tableName || request.tableName || params.tableName || plan.tableName) }
  ]
  if (type === 'forecast') {
    rows.push(
      { label: '时间字段', value: compactText(mapping.timeField || request.timeField || params.timeField) },
      { label: '指标字段', value: compactText(mapping.metricField || request.metricField || params.metricField || result.metricField) },
      { label: '展示指标', value: compactText(mapping.metricLabel || result.metric || plan.metricLabel) },
      { label: '聚合粒度', value: compactText(mapping.granularity || request.granularity || params.granularity || result.granularity) },
      { label: '过滤条件', value: compactText(mapping.filterExpression || request.filterExpression || params.filterExpression) || '无' },
      { label: '算法', value: compactText(mapping.algorithm || request.algorithm || params.algorithm || result.algorithm) }
    )
  } else if (type === 'whatIf') {
    const formulaValue = compactText(mapping.formula || request.formula || params.formula || result.formula)
    rows.push(
      { label: '目标指标', value: compactText(mapping.targetMetric || request.targetMetric || params.targetMetric || result.targetMetric) },
      { label: '展示指标', value: compactText(mapping.metricLabel || plan.metricLabel) },
      { label: '业务公式', value: formulaValue || '未配置' },
      { label: '变量映射', value: formatVariableMapping(mapping.variables || request.variables || params.variables || result.variables) || '未配置' }
    )
    if (formulaValue) {
      rows.splice(rows.length - 1, 0, {
        label: '公式口径',
        value: compactText(mapping.formulaScope || request.formulaScope || params.formulaScope || result.formulaScope) === 'row'
          ? '逐行计算后求平均'
          : '字段均值计算'
      })
    }
  }
  return rows.filter(item => compactText(item.value))
}

const canPinPlan = (plan) => {
  if (plan?.planType !== 'forecast') return false
  const chartId = plan?.queryHistoryId ?? plan?.chartId ?? plan?.result?.queryHistoryId ?? plan?.result?.chartId
  return Number.isFinite(Number(chartId)) && Number(chartId) > 0
}

const normalizePlanAnalysis = (plan) => {
  if (!plan) return null
  const result = plan.result || {}
  if (result.title && result.type && Array.isArray(result.series)) {
    return {
      ...result,
      id: `plan-${plan.id}`,
      status: `已保存 v${plan.versionNo || 1}`,
      planId: plan.id
    }
  }
  if (plan.planType === 'forecast') {
    const algorithm = result.algorithm || plan.request?.algorithm || 'Holt-Winters'
    const horizon = plan.request?.horizon || 3
    return {
      id: `plan-${plan.id}`,
      type: 'forecast',
      title: plan.planName || '时序预测方案',
      summary: '已基于保存参数重新计算预测结果。',
      tableName: plan.tableName || result.tableName || '',
      metric: plan.metricLabel || result.metricField || '核心指标',
      timeRange: plan.timeRangeLabel || result.granularity || '自定义周期',
      status: `已保存 v${plan.versionNo || 1}`,
      params: {
        ...(result.algorithmParams || {}),
        horizon,
        algorithm,
        confidence: result.confidence || '95%',
        algorithmParams: result.algorithmParams || {}
      },
      dataQuality: result.dataQuality || null,
      explanation: result.explanation || null,
      series: Array.isArray(result.series) ? result.series : [],
      insights: Array.isArray(result.insights)
        ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
        : []
    }
  }
  return {
    id: `plan-${plan.id}`,
    type: 'whatIf',
    title: plan.planName || 'What-if 推演方案',
    summary: '已基于保存参数重新计算推演结果。',
    tableName: plan.tableName || result.tableName || '',
    metric: plan.metricLabel || result.targetMetric || '目标指标',
    timeRange: plan.timeRangeLabel || '当前分析周期',
    status: `已保存 v${plan.versionNo || 1}`,
    params: {
      targetMetric: result.targetMetric || plan.request?.targetMetric || '',
      formula: result.formula || plan.request?.formula || '',
      formulaScope: result.formulaScope || plan.request?.formulaScope || 'aggregate',
      resolvedFormula: result.resolvedFormula || '',
      calculationMode: result.calculationMode || (result.formula || plan.request?.formula ? 'formula' : 'regression'),
      variables: Array.isArray(result.variables) ? result.variables : (plan.request?.variables || [])
    },
    explanation: result.explanation || null,
    series: Array.isArray(result.series) ? result.series : [],
    insights: Array.isArray(result.insights)
      ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
      : []
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    const [ruleRows, eventRows, pushRows, pushConfigRows, planRows] = await Promise.all([
      listAdvancedAlertRules(),
      listAdvancedAlertEvents(),
      listAdvancedAlertPushLogs(),
      fetchAdvancedAlertPushConfig(),
      listAdvancedAnalysisPlans()
    ])
    rules.value = Array.isArray(ruleRows) ? ruleRows.filter(item => item.status !== 'DELETED') : []
    events.value = Array.isArray(eventRows) ? eventRows : []
    pushLogs.value = Array.isArray(pushRows) ? pushRows : []
    pushConfig.value = pushConfigRows || {}
    plans.value = Array.isArray(planRows) ? planRows : []
    selectedRules.value = []
    rulesTableRef.value?.clearSelection?.()
    selectedPlans.value = []
    plansTableRef.value?.clearSelection?.()
  } catch (error) {
    ElMessage.error(`加载预测与情景模拟数据失败：${error.message || '未知原因'}`)
  } finally {
    loading.value = false
  }
}

const syncEventRow = (updated) => {
  if (!updated?.id) return
  const index = events.value.findIndex(item => String(item.id) === String(updated.id))
  if (index >= 0) {
    events.value.splice(index, 1, updated)
  }
  if (eventSnapshotTarget.value?.id && String(eventSnapshotTarget.value.id) === String(updated.id)) {
    eventSnapshotTarget.value = updated
    eventExplanationNote.value = String(updated.explanationNote || '')
  }
}

const openPlan = async (plan) => {
  if (!plan?.id) return
  try {
    selectedPlan.value = await getAdvancedAnalysisPlan(plan.id)
    planDetailVisible.value = true
  } catch (error) {
    ElMessage.error(`打开方案失败：${error.message || '未知原因'}`)
  }
}

const recalculatePlan = async (plan) => {
  if (!plan?.id) return
  planRecalculating.value = true
  try {
    const updated = await recalculateAdvancedAnalysisPlan(plan.id)
    const index = plans.value.findIndex(item => String(item.id) === String(plan.id))
    if (index >= 0) {
      plans.value.splice(index, 1, updated)
    }
    if (selectedPlan.value && String(selectedPlan.value.id) === String(plan.id)) {
      selectedPlan.value = updated
    }
    ElMessage.success(`方案已复算至 v${updated?.versionNo || ''}`)
  } catch (error) {
    ElMessage.error(`复算失败：${error.message || '未知原因'}`)
  } finally {
    planRecalculating.value = false
  }
}

const recalculateSelectedPlan = async () => {
  if (!selectedPlan.value?.id) return
  await recalculatePlan(selectedPlan.value)
}

const applyExplanationToVersionSnapshot = (side, explanation) => {
  if (!versionCompareResult.value?.[side]) return false
  versionCompareResult.value = {
    ...versionCompareResult.value,
    [side]: {
      ...versionCompareResult.value[side],
      result: {
        ...(versionCompareResult.value[side].result || {}),
        explanation
      }
    }
  }
  return true
}

const applyPlanExplanation = (analysis, explanation) => {
  const analysisId = String(analysis?.id || '')
  if (analysisId.startsWith('plan-') && selectedPlan.value) {
    selectedPlan.value = {
      ...selectedPlan.value,
      result: {
        ...(selectedPlan.value.result || {}),
        explanation
      }
    }
    const planIndex = plans.value.findIndex(item => String(item.id) === String(selectedPlan.value.id))
    if (planIndex >= 0) {
      plans.value.splice(planIndex, 1, selectedPlan.value)
    }
    return
  }
  const leftId = leftVersionAnalysis.value?.id
  const rightId = rightVersionAnalysis.value?.id
  if (analysisId && analysisId === leftId && applyExplanationToVersionSnapshot('left', explanation)) return
  if (analysisId && analysisId === rightId && applyExplanationToVersionSnapshot('right', explanation)) return
}

const buildAdvancedExplanationPayloadResult = (analysis = {}) => ({
  type: analysis.type || '',
  title: analysis.title || '',
  summary: analysis.summary || '',
  tableName: analysis.tableName || analysis.params?.tableName || '',
  metric: analysis.metric || '',
  timeRange: analysis.timeRange || '',
  status: analysis.status || '',
  params: {
    ...(analysis.params || {}),
    variables: Array.isArray(analysis.params?.variables)
      ? analysis.params.variables.map(item => ({ ...item }))
      : []
  },
  formula: analysis.params?.formula || analysis.formula || '',
  resolvedFormula: analysis.params?.resolvedFormula || analysis.resolvedFormula || '',
  series: Array.isArray(analysis.series) ? analysis.series.map(item => ({ ...item })) : [],
  insights: Array.isArray(analysis.insights) ? analysis.insights.map(item => ({ ...item })) : [],
  explanation: analysis.explanation && typeof analysis.explanation === 'object'
    ? {
        source: analysis.explanation.source || '',
        sourceLabel: analysis.explanation.sourceLabel || '',
        calculation: Array.isArray(analysis.explanation.calculation) ? [...analysis.explanation.calculation] : [],
        suggestions: Array.isArray(analysis.explanation.suggestions) ? [...analysis.explanation.suggestions] : []
      }
    : null
})

const explainPlanAnalysis = async (analysis) => {
  if (!analysis?.id) return
  advancedAnalysisExplainingId.value = analysis.id
  try {
    const explanation = await explainAdvancedAnalysisResult({
      type: analysis.type,
      question: analysis.title || selectedPlan.value?.planName || '',
      result: buildAdvancedExplanationPayloadResult(analysis),
      context: {
        tableName: analysis.tableName || selectedPlan.value?.tableName || '',
        metric: analysis.metric || selectedPlan.value?.metricLabel || '',
        source: 'advanced-analysis-manage'
      }
    })
    applyPlanExplanation(analysis, explanation)
    ElMessage.success(explanation?.source === 'llm' ? 'AI 解释已生成' : '已生成规则解释兜底')
  } catch (error) {
    ElMessage.error(`生成解释失败：${error.message || '未知原因'}`)
  } finally {
    advancedAnalysisExplainingId.value = ''
  }
}

const renamePlan = async (plan) => {
  if (!plan?.id) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新的方案名称', '重命名方案', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: plan.planName || '',
      inputPlaceholder: '例如：华东销售额未来 3 个月预测',
      inputValidator: value => {
        const name = String(value || '').trim()
        if (!name) return '方案名称不能为空'
        if (name.length > 200) return '方案名称不能超过 200 个字符'
        return true
      }
    })
    const updated = await renameAdvancedAnalysisPlan({ id: plan.id, planName: String(value || '').trim() })
    const index = plans.value.findIndex(item => String(item.id) === String(plan.id))
    if (index >= 0) {
      plans.value.splice(index, 1, updated)
    }
    if (selectedPlan.value && String(selectedPlan.value.id) === String(plan.id)) {
      selectedPlan.value = updated
    }
    ElMessage.success('方案名称已更新')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(`重命名失败：${error.message || '未知原因'}`)
  }
}

const snapshotToAnalysis = (snapshot) => {
  if (!snapshot) return null
  const basePlan = selectedPlan.value || {}
  const mergedPlan = {
    ...basePlan,
    id: snapshot.planId || basePlan.id || snapshot.id || snapshot.versionNo,
    planType: snapshot.planType || basePlan.planType,
    planName: snapshot.planName || basePlan.planName,
    tableName: basePlan.tableName || snapshot.tableName || snapshot.request?.tableName || snapshot.result?.tableName || '',
    metricLabel: basePlan.metricLabel || snapshot.metricLabel || snapshot.request?.metric || snapshot.result?.metricField || snapshot.result?.targetMetric || '',
    timeRangeLabel: basePlan.timeRangeLabel || snapshot.timeRangeLabel || snapshot.request?.granularity || snapshot.result?.granularity || '',
    versionNo: snapshot.versionNo,
    request: snapshot.request || {},
    result: snapshot.result || {},
    llm: snapshot.llm || {}
  }
  const analysis = normalizePlanAnalysis(mergedPlan)
  if (!analysis) {
    return null
  }
  return {
    ...analysis,
    title: `${snapshot.planName || basePlan.planName || analysis.title} / v${snapshot.versionNo || 1}`,
    status: `版本 v${snapshot.versionNo || 1}`
  }
}

const removePlan = async (plan) => {
  if (!plan?.id) return
  try {
    await deleteAdvancedAnalysisPlan(plan.id)
    plans.value = plans.value.filter(item => String(item.id) !== String(plan.id))
    if (selectedPlan.value && String(selectedPlan.value.id) === String(plan.id)) {
      selectedPlan.value = null
      planDetailVisible.value = false
    }
    ElMessage.success('方案已删除')
  } catch (error) {
    ElMessage.error(`删除方案失败：${error.message || '未知原因'}`)
  }
}

const handlePlanSelectionChange = (selection) => {
  selectedPlans.value = Array.isArray(selection) ? selection : []
}

const batchRemovePlans = async () => {
  const ids = selectedPlanIds.value
  if (!ids.length) {
    ElMessage.warning('请先选择需要删除的方案')
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 个方案吗？删除后不会再出现在方案资产列表中。`, '批量删除方案', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    batchPlanOperating.value = true
    const result = await batchDeleteAdvancedAnalysisPlans(ids)
    const idSet = new Set(ids.map(String))
    plans.value = plans.value.filter(item => !idSet.has(String(item.id)))
    selectedPlans.value = []
    plansTableRef.value?.clearSelection?.()
    if (selectedPlan.value && idSet.has(String(selectedPlan.value.id))) {
      selectedPlan.value = null
      planDetailVisible.value = false
      planVersionVisible.value = false
    }
    await loadAll()
    ElMessage.success(`已删除 ${result?.updated ?? ids.length} 个方案`)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(`批量删除方案失败：${error.message || '未知原因'}`)
  } finally {
    batchPlanOperating.value = false
  }
}

const loadDashboardOptions = async () => {
  const rows = await listPinTargetDashboards()
  dashboardOptions.value = Array.isArray(rows)
    ? rows.map(item => ({
        id: Number(item.id),
        name: String(item.name || `看板#${item.id}`),
        isPublic: Boolean(item.isPublic),
        pinTargetLabel: String(item.pinTargetLabel || '').trim(),
        status: String(item.status || '').trim()
      })).filter(item => Number.isFinite(item.id) && item.id > 0 && item.status !== 'ACTIVE')
    : []
  if (!pinDashboardId.value && dashboardOptions.value.length) {
    pinDashboardId.value = dashboardOptions.value[0].id
  }
}

const formatPinDashboardLabel = (dashboard) => {
  const name = String(dashboard?.name || `看板#${dashboard?.id || ''}`).trim()
  const tag = String(dashboard?.pinTargetLabel || '').trim()
  if (tag) return `${name}（${tag}）`
  return dashboard?.isPublic ? `${name}（公开）` : name
}

const openPinPlanDialog = async (plan) => {
  if (!canPinPlan(plan)) {
    ElMessage.warning('当前方案缺少可钉入看板的预测图表记录，请先保存或复算真实预测方案')
    return
  }
  try {
    await loadDashboardOptions()
    if (!dashboardOptions.value.length) {
      ElMessage.warning('暂无可用看板，请先到“我的看板”创建')
      return
    }
    pinPlanTarget.value = plan
    pinPlanVisible.value = true
  } catch (error) {
    ElMessage.error(`加载看板列表失败：${error.message || '未知原因'}`)
  }
}

const pinPlanToDashboard = async () => {
  const plan = pinPlanTarget.value
  const dashboardId = Number(pinDashboardId.value)
  const chartId = Number(plan?.queryHistoryId ?? plan?.chartId ?? plan?.result?.queryHistoryId ?? plan?.result?.chartId)
  if (!plan || !Number.isFinite(dashboardId) || dashboardId <= 0) {
    ElMessage.warning('请选择目标看板')
    return
  }
  if (!Number.isFinite(chartId) || chartId <= 0) {
    ElMessage.warning('当前方案缺少可绑定的图表记录')
    return
  }
  pinningPlan.value = true
  try {
    const result = plan.result || {}
    const algorithmParams = result.algorithmParams || {}
    const fieldMapping = plan.fieldMapping || {}
    await pinChartToDashboard(dashboardId, {
      chartId,
      title: String(plan.planName || '预测图表').slice(0, 80),
      chartType: 'line',
      tableName: plan.tableName || '',
      fieldMapping: {
        dimension: '预测周期',
        dimensionKey: 'name',
        metric: plan.metricLabel || '预测值',
        metricKey: 'value'
      },
      advancedAnalysisPlanId: plan.id,
      advancedAnalysisPlanVersion: plan.versionNo || 1,
      advancedAnalysisType: plan.planType || 'forecast',
      advancedAnalysisAction: {
        type: 'advanced-analysis-plan-recalculate',
        label: '重新计算预测',
        planId: plan.id,
        planVersion: plan.versionNo || 1
      },
      forecastMeta: {
        algorithm: result.algorithm || algorithmParams.algorithm || 'Holt-Winters',
        confidence: result.confidence || '95%',
        algorithmParams,
        granularity: result.granularity || fieldMapping.granularity || '',
        timeField: result.timeField || fieldMapping.timeField || '',
        metricField: result.metricField || fieldMapping.metricField || '',
        filterExpression: result.filterExpression || fieldMapping.filterExpression || ''
      }
    })
    pinPlanVisible.value = false
    ElMessage.success('预测图表已钉入看板')
  } catch (error) {
    ElMessage.error(`钉入看板失败：${error.message || '未知原因'}`)
  } finally {
    pinningPlan.value = false
  }
}

const normalizeVersionSelection = (versions, preserveSelection = false) => {
  const versionNos = versions
    .map(item => Number(item.versionNo))
    .filter(item => Number.isFinite(item) && item > 0)
  if (!versionNos.length) {
    versionCompareForm.leftVersion = ''
    versionCompareForm.rightVersion = ''
    return
  }
  const latestVersion = versionNos[0]
  const previousVersion = versionNos.find(item => item !== latestVersion) || latestVersion
  if (
    preserveSelection &&
    versionNos.includes(Number(versionCompareForm.leftVersion)) &&
    versionNos.includes(Number(versionCompareForm.rightVersion))
  ) {
    return
  }
  versionCompareForm.leftVersion = previousVersion
  versionCompareForm.rightVersion = latestVersion
}

const loadPlanVersions = async (plan, preserveSelection = false) => {
  const targetPlan = plan?.id ? plan : plan?.value || null
  if (!targetPlan?.id) {
    return
  }
  try {
    const [versionsResult, compareResult] = await Promise.allSettled([
      listAdvancedAnalysisPlanVersions(targetPlan.id),
      compareLatestAdvancedAnalysisPlanVersions(targetPlan.id)
    ])
    if (versionsResult.status === 'rejected') {
      throw versionsResult.reason
    }
    const rows = Array.isArray(versionsResult.value)
      ? versionsResult.value
          .map(item => ({
            ...item,
            versionNo: Number(item.versionNo || 0)
          }))
          .filter(item => Number.isFinite(item.versionNo) && item.versionNo > 0)
      : []
    rows.sort((a, b) => b.versionNo - a.versionNo)
    planVersions.value = rows
    normalizeVersionSelection(rows, preserveSelection)

    if (
      compareResult.status === 'fulfilled' &&
      compareResult.value &&
      compareResult.value.left &&
      compareResult.value.right
    ) {
      versionCompareResult.value = compareResult.value
    } else if (!preserveSelection) {
      versionCompareResult.value = null
    }
  } catch (error) {
    ElMessage.error(`加载版本失败：${error.message || '未知原因'}`)
  }
}

const openPlanVersions = async (plan) => {
  if (!plan?.id) return
  selectedPlan.value = plan
  planVersionVisible.value = true
  versionCompareResult.value = null
  await loadPlanVersions(plan)
}

const applyVersionToCompare = (versionNo, side) => {
  const normalizedVersion = Number(versionNo)
  if (!Number.isFinite(normalizedVersion) || normalizedVersion <= 0) return
  if (side !== 'left' && side !== 'right') return
  versionCompareForm[`${side}Version`] = normalizedVersion
}

const compareSelectedPlanVersions = async () => {
  const plan = selectedPlan.value
  if (!plan?.id) return
  const leftVersion = Number(versionCompareForm.leftVersion)
  const rightVersion = Number(versionCompareForm.rightVersion)
  if (!Number.isFinite(leftVersion) || !Number.isFinite(rightVersion) || leftVersion <= 0 || rightVersion <= 0) {
    ElMessage.warning('请先选择两个有效版本')
    return
  }
  if (leftVersion === rightVersion) {
    ElMessage.warning('左右版本不能相同')
    return
  }
  versionComparing.value = true
  try {
    const result = await compareAdvancedAnalysisPlanVersions({
      id: plan.id,
      leftVersion,
      rightVersion
    })
    versionCompareResult.value = result && result.left && result.right ? result : null
    if (!versionCompareResult.value && result?.message) {
      ElMessage.info(result.message)
    }
  } catch (error) {
    ElMessage.error(`版本对比失败：${error.message || '未知原因'}`)
  } finally {
    versionComparing.value = false
  }
}

const goChat = () => {
  if (workbench?.activeModule) {
    workbench.activeModule.value = 'chat'
  }
}

const setWorkbenchTable = (tableName) => {
  const value = String(tableName || '').trim()
  if (value && workbench?.selectedTableName) {
    workbench.selectedTableName.value = value
  }
}

const restoreAlertContextToChat = ({ event, pushLog = null, sourceLabel = '预警事件' } = {}) => {
  if (!workbench?.activeModule) {
    ElMessage.warning('当前页面无法访问对话查询上下文')
    return
  }
  const normalizedEvent = event || {}
  setWorkbenchTable(normalizedEvent.tableName)
  if (workbench.advancedAlertContext) {
    workbench.advancedAlertContext.value = {
      type: 'alert-event',
      sourceLabel,
      event: normalizedEvent,
      pushLog,
      restoredAt: Date.now()
    }
  }
  workbench.activeModule.value = 'chat'
}

const goChatWithAlertEvent = async (row) => {
  if (!row?.id) return
  try {
    const detail = await getAdvancedAlertEvent(row.id)
    const index = events.value.findIndex(item => String(item.id) === String(row.id))
    if (index >= 0) {
      events.value.splice(index, 1, detail)
    }
    restoreAlertContextToChat({ event: detail || row, sourceLabel: '预警事件列表' })
  } catch (error) {
    ElMessage.error(`恢复预警上下文失败：${error.message || '未知原因'}`)
  }
}

const goChatWithPushLog = async (row) => {
  if (!row?.eventId) {
    ElMessage.warning('该推送记录缺少关联预警事件')
    return
  }
  restoringPushId.value = row.id
  try {
    const event = await getAdvancedAlertEvent(row.eventId)
    restoreAlertContextToChat({ event, pushLog: row, sourceLabel: '推送记录' })
  } catch (error) {
    ElMessage.error(`恢复推送上下文失败：${error.message || '未知原因'}`)
  } finally {
    restoringPushId.value = ''
  }
}

const editRule = async (rule) => {
  if (!rule?.id) return
  try {
    const detail = await getAdvancedAlertRule(rule.id)
    const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName: detail.tableName })
    editorMeta.value = {
      timeFields: Array.isArray(fieldMeta?.timeFields) ? fieldMeta.timeFields : [],
      numericFields: Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
    }
    editorForm.value = {
      id: detail.id,
      tableName: detail.tableName || '',
      timeField: detail.timeField || '',
      metricField: detail.metricField || '',
      filterExpression: detail.filterExpression || '',
      granularity: detail.granularity || 'day',
      operator: detail.operator || 'lt',
      threshold: Number(detail.threshold ?? 100000),
      detectionCycle: detail.detectionCycle || 'daily',
      channels: Array.isArray(detail.channels) && detail.channels.length ? detail.channels : ['email', 'dingtalk'],
      status: detail.status || 'ACTIVE'
    }
    editorVisible.value = true
  } catch (error) {
    ElMessage.error(`打开规则失败：${error.message || '未知原因'}`)
  }
}

const submitEditor = async () => {
  if (!editorForm.value.timeField || !editorForm.value.metricField) {
    ElMessage.warning('请选择时间字段和指标字段')
    return
  }
  saving.value = true
  try {
    await updateAdvancedAlertRule(editorForm.value)
    editorVisible.value = false
    await loadAll()
    ElMessage.success('预警规则已更新')
  } catch (error) {
    ElMessage.error(`保存失败：${error.message || '未知原因'}`)
  } finally {
    saving.value = false
  }
}

const handleRuleSelectionChange = (selection) => {
  selectedRules.value = Array.isArray(selection) ? selection : []
}

const toggleRule = async (rule) => {
  const status = rule.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await updateAdvancedAlertRuleStatus({ id: rule.id, status })
    await loadAll()
    ElMessage.success(status === 'ACTIVE' ? '预警规则已启用' : '预警规则已停用')
  } catch (error) {
    ElMessage.error(`状态更新失败：${error.message || '未知原因'}`)
  }
}

const batchDisableRules = async () => {
  const ids = selectedRuleIds.value
  if (!ids.length) {
    ElMessage.warning('请先选择需要停用的预警规则')
    return
  }
  batchRuleOperating.value = true
  try {
    const result = await batchUpdateAdvancedAlertRuleStatus({ ids, status: 'DISABLED' })
    await loadAll()
    ElMessage.success(`已停用 ${result?.updated ?? ids.length} 条预警规则`)
  } catch (error) {
    ElMessage.error(`批量停用失败：${error.message || '未知原因'}`)
  } finally {
    batchRuleOperating.value = false
  }
}

const removeRule = async (rule) => {
  try {
    await deleteAdvancedAlertRule({ id: rule.id })
    await loadAll()
    ElMessage.success('预警规则已删除')
  } catch (error) {
    ElMessage.error(`删除失败：${error.message || '未知原因'}`)
  }
}

const batchRemoveRules = async () => {
  const ids = selectedRuleIds.value
  if (!ids.length) {
    ElMessage.warning('请先选择需要删除的预警规则')
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条预警规则吗？删除后不会再参与离线检测。`, '批量删除预警规则', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    batchRuleOperating.value = true
    const result = await batchDeleteAdvancedAlertRules({ ids })
    await loadAll()
    ElMessage.success(`已删除 ${result?.updated ?? ids.length} 条预警规则`)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(`批量删除失败：${error.message || '未知原因'}`)
  } finally {
    batchRuleOperating.value = false
  }
}

const runDetection = async (rule) => {
  try {
    const result = await runAdvancedAlertDetection({ ruleId: rule.id, force: true })
    await loadAll()
    ElMessage.success(`检测完成，检查 ${result?.checkedRules || 0} 条规则，新增 ${result?.createdEvents || 0} 条预警事件，刷新 ${result?.refreshedEvents || 0} 条快照`)
  } catch (error) {
    ElMessage.error(`检测失败：${error.message || '未知原因'}`)
  }
}

const renderEventSnapshotChart = async () => {
  await nextTick()
  if (!eventSnapshotChartRef.value || !eventSnapshotTarget.value) return
  if (!eventSnapshotChart) {
    eventSnapshotChart = echarts.init(eventSnapshotChartRef.value)
  }
  eventSnapshotChart.setOption(buildSnapshotOption(eventSnapshotTarget.value), true)
  eventSnapshotChart.resize()
}

const openEventSnapshot = async (event) => {
  if (!event?.id) return
  try {
    eventSnapshotTarget.value = await getAdvancedAlertEvent(event.id)
    eventExplanationNote.value = String(eventSnapshotTarget.value.explanationNote || '')
    syncEventRow(eventSnapshotTarget.value)
    eventSnapshotVisible.value = true
    await renderEventSnapshotChart()
  } catch (error) {
    ElMessage.error(`打开快照失败：${error.message || '未知原因'}`)
  }
}

const generateEventExplanation = async () => {
  const event = eventSnapshotTarget.value
  if (!event?.id) return
  eventExplaining.value = true
  try {
    const updated = await explainAdvancedAlertEvent({
      id: event.id,
      question: `解释预警事件 ${formatEventTitle(event)}`,
      explanationNote: eventExplanationNote.value
    })
    syncEventRow(updated)
    ElMessage.success(updated?.llmExplanation?.source === 'llm' ? 'AI 解释已生成并保存' : '已生成规则解释并保存')
  } catch (error) {
    ElMessage.error(`生成预警解释失败：${error.message || '未知原因'}`)
  } finally {
    eventExplaining.value = false
  }
}

const saveEventExplanationNote = async () => {
  const event = eventSnapshotTarget.value
  if (!event?.id) return
  eventExplanationSaving.value = true
  try {
    const updated = await updateAdvancedAlertEventStatus({
      id: event.id,
      status: event.status || 'OPEN',
      explanationNote: eventExplanationNote.value
    })
    syncEventRow(updated)
    ElMessage.success('解释备注已保存')
  } catch (error) {
    ElMessage.error(`保存解释备注失败：${error.message || '未知原因'}`)
  } finally {
    eventExplanationSaving.value = false
  }
}

const retryPush = async (row) => {
  if (!row?.id) return
  retryingPushId.value = row.id
  try {
    const updated = await retryAdvancedAlertPush(row.id)
    const index = pushLogs.value.findIndex(item => String(item.id) === String(row.id))
    if (index >= 0) {
      pushLogs.value.splice(index, 1, updated)
    }
    ElMessage.success(`推送重试完成：${pushStatusLabel(updated?.status)}`)
  } catch (error) {
    ElMessage.error(`推送重试失败：${error.message || '未知原因'}`)
  } finally {
    retryingPushId.value = ''
  }
}

const openEventStatusDialog = (event, status = 'ACK') => {
  if (!event?.id) return
  eventStatusTarget.value = event
  eventStatusForm.status = String(status || event.status || 'ACK').toUpperCase()
  eventStatusForm.handleNote = String(event.handleNote || '')
  eventStatusVisible.value = true
}

const submitEventStatus = async () => {
  const event = eventStatusTarget.value
  if (!event?.id) return
  eventStatusSaving.value = true
  try {
    const updated = await updateAdvancedAlertEventStatus({
      id: event.id,
      status: eventStatusForm.status,
      handleNote: eventStatusForm.handleNote
    })
    syncEventRow(updated)
    eventStatusVisible.value = false
    ElMessage.success('预警事件处理状态已更新')
  } catch (error) {
    ElMessage.error(`更新预警事件失败：${error.message || '未知原因'}`)
  } finally {
    eventStatusSaving.value = false
  }
}

watch(eventSnapshotVisible, (visible) => {
  if (visible) {
    renderEventSnapshotChart()
    return
  }
  if (eventSnapshotChart) {
    eventSnapshotChart.dispose()
    eventSnapshotChart = null
  }
})

watch(() => rules.value.length, total => clampTablePage('rules', total))
watch(() => events.value.length, total => clampTablePage('events', total))
watch(() => pushLogs.value.length, total => clampTablePage('push', total))
watch(() => plans.value.length, total => clampTablePage('plans', total))

onMounted(loadAll)

onBeforeUnmount(() => {
  if (eventSnapshotChart) {
    eventSnapshotChart.dispose()
    eventSnapshotChart = null
  }
})
</script>

<style scoped>
.advanced-manage-page {
  display: grid;
  gap: 16px;
}
.advanced-manage-header,
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.advanced-manage-header h1,
.panel-head h2,
.empty-plan h2 {
  margin: 0;
  color: #0f172a;
}
.advanced-manage-header p,
.panel-head p,
.empty-plan p,
.rule-meta {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.6;
}
.advanced-manage-header__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.advanced-manage-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.metric-card {
  display: grid;
  gap: 6px;
  padding: 14px 16px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
}
.metric-card.is-green {
  border-color: #bbf7d0;
  background: #f0fdf4;
}
.metric-card.is-orange {
  border-color: #fed7aa;
  background: #fff7ed;
}
.metric-card.is-cyan {
  border-color: #a5f3fc;
  background: #ecfeff;
}
.metric-card.is-purple {
  border-color: #ddd6fe;
  background: #f5f3ff;
}
.metric-card span,
.metric-card small {
  color: #64748b;
  font-size: 12px;
}
.metric-card strong {
  color: #0f172a;
  font-size: 24px;
}
.manage-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.rule-title {
  color: #0f172a;
  font-weight: 700;
  line-height: 1.5;
}
.schedule-meta {
  display: grid;
  gap: 3px;
  color: #475569;
  font-size: 12px;
  line-height: 1.45;
}
.event-note {
  display: block;
  overflow: hidden;
  color: #475569;
  font-size: 13px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.push-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.push-config-card {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.push-config-card span,
.push-config-card small,
.push-config-card em {
  color: #64748b;
  font-size: 12px;
  font-style: normal;
}
.push-config-card strong {
  color: #0f172a;
  font-size: 18px;
}
.alert-snapshot {
  display: grid;
  gap: 12px;
}
.alert-snapshot__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.alert-snapshot__summary > div {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}
.alert-snapshot__summary span {
  color: #64748b;
  font-size: 12px;
}
.alert-snapshot__summary strong {
  color: #0f172a;
  font-size: 15px;
  line-height: 1.4;
  word-break: break-word;
}
.alert-snapshot__chart {
  width: 100%;
  height: 320px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.alert-snapshot__reason {
  padding: 10px 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
  color: #9a3412;
  font-size: 13px;
  line-height: 1.6;
}
.alert-explain-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}
.alert-explain-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.alert-explain-panel__head > div {
  display: grid;
  gap: 3px;
}
.alert-explain-panel__head strong {
  color: #0f172a;
  font-size: 14px;
}
.alert-explain-panel__head span {
  color: #64748b;
  font-size: 12px;
}
.alert-explain-panel__content {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.alert-explain-panel__content > div {
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.alert-explain-panel__content h4 {
  margin: 0 0 8px;
  color: #0f172a;
  font-size: 13px;
}
.alert-explain-panel__content ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: #475569;
  font-size: 13px;
  line-height: 1.55;
}
.rule-editor-form {
  display: grid;
  gap: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.full-width {
  width: 100%;
}
.empty-plan {
  display: grid;
  justify-items: start;
  gap: 10px;
  padding: 24px;
}
.plan-detail {
  display: grid;
  gap: 12px;
}
.plan-detail__meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: #64748b;
  font-size: 12px;
}
.field-mapping-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
}
.field-mapping-panel--compact {
  margin-bottom: 10px;
  padding: 10px;
}
.field-mapping-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #2563eb;
  font-size: 13px;
}
.field-mapping-panel__head strong {
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
}
.field-mapping-panel__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.field-mapping-panel__item {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.field-mapping-panel__item span {
  color: #64748b;
  font-size: 12px;
}
.field-mapping-panel__item strong {
  overflow: hidden;
  color: #0f172a;
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plan-version-panel {
  display: grid;
  gap: 12px;
}
.version-alert {
  margin-bottom: 2px;
}
.plan-version-panel__toolbar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.plan-version-compare {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  align-items: start;
}
.plan-version-compare__col {
  min-width: 0;
  display: grid;
  gap: 8px;
}
.plan-version-compare__col h4 {
  margin: 0;
  color: #0f172a;
}
.plan-version-compare__col :deep(.advanced-card) {
  width: 100%;
  margin-top: 0;
}
@media (max-width: 900px) {
  .advanced-manage-header,
  .panel-head {
    align-items: stretch;
    flex-direction: column;
  }
  .advanced-manage-metrics,
  .form-grid,
  .push-config-grid,
  .plan-version-compare,
  .alert-explain-panel__content,
  .alert-snapshot__summary {
    grid-template-columns: 1fr;
  }
}

.advanced-manage-page {
  gap: 14px;
  color: #172033;
}

.advanced-manage-header {
  min-height: 104px;
  padding: 18px 20px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(15, 35, 71, 0.06);
}

.advanced-manage-header__copy {
  min-width: 0;
  display: grid;
  gap: 6px;
}

.module-kicker {
  width: fit-content;
  padding: 3px 9px;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #f8fbff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.advanced-manage-header h1 {
  color: #0f2347;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.2;
}

.advanced-manage-header p {
  max-width: 780px;
  margin-top: 0;
  color: #62728a;
  font-size: 13px;
  line-height: 1.65;
}

.advanced-manage-header__actions {
  align-items: center;
  justify-content: flex-end;
}

.advanced-manage-header__actions :deep(.el-button),
.panel-head :deep(.el-button) {
  min-height: 34px;
  border-radius: 6px;
  font-weight: 700;
}

.advanced-manage-header__actions :deep(.el-button--primary),
.panel-head :deep(.el-button--primary) {
  background: #2563eb;
  border-color: #2563eb;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.18);
}

.advanced-manage-metrics {
  grid-template-columns: repeat(5, minmax(150px, 1fr));
  gap: 10px;
}

.metric-card {
  min-width: 0;
  min-height: 86px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f7faff 100%);
  box-shadow: 0 8px 20px rgba(15, 35, 71, 0.04);
}

.metric-card.is-green,
.metric-card.is-orange,
.metric-card.is-cyan,
.metric-card.is-purple {
  border-color: #dce5f2;
  background: linear-gradient(180deg, #ffffff 0%, #f7faff 100%);
}

.metric-card .metric-card__icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #2563eb;
  font-size: 22px;
  background: #edf4ff;
}

.metric-card.is-green .metric-card__icon {
  color: #16a34a;
  background: #ecfdf3;
}

.metric-card.is-orange .metric-card__icon {
  color: #f97316;
  background: #fff7ed;
}

.metric-card.is-cyan .metric-card__icon {
  color: #0891b2;
  background: #ecfeff;
}

.metric-card.is-purple .metric-card__icon {
  color: #7c3aed;
  background: #f5f3ff;
}

.metric-card__body {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.metric-card__body span {
  color: #62728a;
  font-size: 12px;
  font-weight: 700;
}

.metric-card__body strong {
  color: #0f2347;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.05;
}

.metric-card__body small {
  overflow: hidden;
  color: #7a8799;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.advanced-manage-tabs {
  padding: 0 16px 16px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 24px rgba(15, 35, 71, 0.05);
}

.advanced-manage-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.advanced-manage-tabs :deep(.el-tabs__nav-wrap) {
  padding: 0 2px;
}

.advanced-manage-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: #e8edf5;
}

.advanced-manage-tabs :deep(.el-tabs__item) {
  height: 50px;
  padding: 0 18px;
  color: #5d6d84;
  font-size: 14px;
  font-weight: 800;
}

.advanced-manage-tabs :deep(.el-tabs__item.is-active) {
  color: #2563eb;
}

.advanced-manage-tabs :deep(.el-tabs__active-bar) {
  height: 2px;
  border-radius: 999px;
  background: #2563eb;
}

.advanced-manage-tabs :deep(.el-tabs__content) {
  padding-top: 14px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tab-label em {
  min-width: 22px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 7px;
  border-radius: 999px;
  background: #eef2f7;
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  line-height: 1;
}

.advanced-manage-tabs :deep(.el-tabs__item.is-active) .tab-label em {
  background: #dbeafe;
  color: #2563eb;
}

.manage-panel {
  gap: 12px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.panel-head {
  min-height: 46px;
  align-items: flex-start;
  padding: 0 2px 2px;
}

.panel-head h2 {
  color: #0f2347;
  font-size: 17px;
  font-weight: 800;
  line-height: 1.25;
}

.panel-head p {
  max-width: 820px;
  margin-top: 4px;
  color: #62728a;
  font-size: 12px;
  line-height: 1.55;
}

.manage-table {
  overflow: hidden;
  border: 1px solid #e1e8f2;
  border-radius: 8px;
  --el-table-border-color: #e8edf5;
  --el-table-header-bg-color: #f8fafd;
  --el-table-row-hover-bg-color: #f6faff;
}

.manage-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.manage-table :deep(th.el-table__cell) {
  background: #f8fafd !important;
  color: #51637d;
  font-size: 12px;
  font-weight: 800;
}

.manage-table :deep(.el-table__cell) {
  padding: 10px 0;
}

.manage-table :deep(.el-table__row) {
  min-height: 54px;
}

.manage-table :deep(.el-button.is-text) {
  min-height: 26px;
  padding: 4px 6px;
  border-radius: 5px;
  font-weight: 700;
}

.table-pagination {
  min-height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 2px 0;
}

.table-pagination > span {
  flex: 0 0 auto;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.table-pagination :deep(.el-pagination) {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.table-pagination :deep(.btn-prev),
.table-pagination :deep(.btn-next),
.table-pagination :deep(.el-pager li) {
  border-radius: 6px;
}

.batch-action-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex: 0 0 auto;
}

.batch-action-bar > span {
  color: #51637d;
  font-size: 12px;
  font-weight: 700;
}

.rule-title {
  color: #102247;
  font-size: 13px;
  font-weight: 800;
}

.rule-meta {
  color: #7a8799;
  font-size: 12px;
  line-height: 1.5;
}

.schedule-meta {
  gap: 4px;
  color: #51637d;
}

.schedule-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-note {
  color: #51637d;
}

.push-config-grid {
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 10px;
}

.push-config-card {
  position: relative;
  min-height: 88px;
  padding: 14px 16px 14px 42px;
  border: 1px solid #e1e8f2;
  border-radius: 8px;
  background: #fbfdff;
}

.push-config-card::before {
  content: '';
  position: absolute;
  top: 18px;
  left: 16px;
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 4px #f1f5f9;
}

.push-config-card.is-available::before {
  background: #22c55e;
  box-shadow: 0 0 0 4px #dcfce7;
}

.push-config-card span {
  color: #62728a;
  font-size: 12px;
  font-weight: 700;
}

.push-config-card strong {
  color: #102247;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.2;
}

.push-config-card small,
.push-config-card em {
  color: #7a8799;
  font-size: 12px;
  line-height: 1.45;
}

.alert-snapshot__summary {
  gap: 10px;
}

.alert-snapshot__summary > div,
.field-mapping-panel,
.alert-explain-panel,
.alert-explain-panel__content > div {
  border-color: #e1e8f2;
  border-radius: 8px;
  background: #fbfdff;
}

.alert-snapshot__summary strong,
.field-mapping-panel__item strong {
  color: #102247;
}

.alert-snapshot__chart {
  border-color: #e1e8f2;
}

.alert-snapshot__reason {
  border-color: #fed7aa;
  border-radius: 8px;
}

.plan-detail__meta span {
  padding: 4px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #51637d;
  font-weight: 700;
}

.field-mapping-panel__head {
  color: #2563eb;
  font-weight: 800;
}

.plan-version-panel__toolbar {
  padding: 12px;
  border: 1px solid #e1e8f2;
  border-radius: 8px;
  background: #fbfdff;
}

.plan-version-compare__col {
  padding: 12px;
  border: 1px solid #e1e8f2;
  border-radius: 8px;
  background: #ffffff;
}

.plan-version-compare__col h4 {
  color: #0f2347;
  font-size: 14px;
  font-weight: 800;
}

.form-grid {
  gap: 12px 14px;
}

:deep(.el-dialog) {
  border-radius: 10px;
}

:deep(.el-dialog__header) {
  margin: 0;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #edf1f7;
}

:deep(.el-dialog__title) {
  color: #0f2347;
  font-size: 16px;
  font-weight: 800;
}

:deep(.el-dialog__body) {
  padding: 16px 20px;
}

:deep(.el-dialog__footer) {
  padding: 12px 20px 16px;
  border-top: 1px solid #edf1f7;
}

:deep(.el-form-item__label) {
  color: #1e2f4d;
  font-size: 12px;
  font-weight: 700;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper),
:deep(.el-textarea__inner) {
  border-radius: 6px;
  box-shadow: 0 0 0 1px #d8e1ee inset;
}

@media (max-width: 1280px) {
  .advanced-manage-metrics {
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  }
}

@media (max-width: 900px) {
  .advanced-manage-header {
    align-items: stretch;
    padding: 16px;
  }

  .advanced-manage-header__actions {
    justify-content: flex-start;
  }

  .panel-head {
    gap: 10px;
  }

  .push-config-grid {
    grid-template-columns: 1fr;
  }

  .table-pagination {
    align-items: flex-start;
    flex-direction: column;
  }

  .table-pagination :deep(.el-pagination) {
    justify-content: flex-start;
  }

  .batch-action-bar {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .advanced-manage-tabs {
    padding-inline: 10px;
  }

  .advanced-manage-tabs :deep(.el-tabs__item) {
    padding: 0 10px;
    font-size: 13px;
  }

  .metric-card {
    min-height: 78px;
  }

  .metric-card__body small {
    white-space: normal;
  }
}

.pin-dialog-hint {
  margin: 0 0 12px;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
}
</style>
