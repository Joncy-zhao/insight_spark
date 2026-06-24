<template>
<section class="chat-layout">
          <div class="panel chat-panel">
            <div class="panel-header">
              <div>
                <h2>自然语言分析</h2>
                <p>选择数据源与业务模型后，输入业务问题即可生成图表与洞察。</p>
              </div>
              <el-button size="small" plain @click="businessDictionaryPanelVisible = true">
                业务字典/公式维护
              </el-button>
            </div>

            <div class="chat-datasource-bar">
              <div class="chat-filter-grid">
                <div class="chat-filter-field">
                  <div class="chat-datasource-label">
                    <span class="chat-datasource-database-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" focusable="false">
                        <ellipse cx="12" cy="5" rx="7" ry="3"></ellipse>
                        <path d="M5 5v6c0 1.66 3.13 3 7 3s7-1.34 7-3V5"></path>
                        <path d="M5 11v6c0 1.66 3.13 3 7 3s7-1.34 7-3v-6"></path>
                      </svg>
                    </span>
                    <span>数据源</span>
                  </div>
                  <el-select
                      v-model="selectedTableName"
                      placeholder="请选择数据源"
                      class="chat-toolbar-select"
                      popper-class="chat-select-dropdown"
                      clearable
                      filterable
                  >
                    <el-option-group v-if="uploadTables.length" label="上传数据表">
                      <el-option
                          v-for="table in uploadTables"
                          :key="table.tableName"
                          :label="formatTableOptionLabel(table)"
                          :value="table.tableName"
                      />
                    </el-option-group>
                    <el-option-group v-if="officialQueryTables.length" label="官方数据表">
                      <el-option
                          v-for="table in officialQueryTables"
                          :key="table.tableName"
                          :label="formatTableOptionLabel(table)"
                          :value="table.tableName"
                      />
                    </el-option-group>
                  </el-select>
                  <div class="chat-selection-hint" :title="selectedTableSummary || ''">
                    {{ selectedTableSummary || '请选择要分析的数据源' }}
                  </div>
                </div>
                <div class="chat-filter-field">
                  <div class="chat-datasource-label">
                    <el-icon><Box /></el-icon>
                    <span>业务模型</span>
                  </div>
                  <el-select
                      :model-value="selectedChatBusinessModelId"
                      placeholder="请选择业务模型"
                      class="chat-toolbar-select"
                      popper-class="chat-select-dropdown"
                      clearable
                      filterable
                      :disabled="!selectedTableName"
                      @change="handleChatBusinessModelChange"
                  >
                    <el-option
                        v-for="model in chatBusinessModelOptions"
                        :key="model.id"
                        :label="formatBusinessModelLabel(model)"
                        :value="model.id"
                    />
                  </el-select>
                  <div class="chat-selection-hint" :title="selectedBusinessModelSummary || ''">
                    {{ selectedBusinessModelSummary || businessModelEmptyHint }}
                  </div>
                </div>
              </div>
            </div>

            <div v-if="chatContentMode === 'sessions'" class="chat-thread-header">
              <button
                  type="button"
                  class="chat-thread-toggle"
                  :title="chatContentMode === 'messages' ? '打开会话管理' : '返回当前对话'"
                  :aria-label="chatContentMode === 'messages' ? '打开会话管理' : '返回当前对话'"
                  @click="toggleChatContentMode"
              >
                <el-icon>
                  <component :is="chatContentMode === 'messages' ? ArrowLeftBold : ArrowRightBold" />
                </el-icon>
              </button>
              <div class="chat-thread-title-wrap">
                <div class="chat-thread-title">{{ chatContentMode === 'messages' ? currentChatSessionTitle : '会话记录' }}</div>
                <div class="chat-thread-subtitle">
                  {{ chatContentMode === 'messages' ? currentChatSessionSubtitle : '查看、切换和管理已有对话' }}
                </div>
              </div>
              <div class="chat-thread-actions">
                <el-button size="small" plain class="chat-thread-history-btn" @click="openHistoryDrawer">
                  历史产物
                </el-button>
                <el-tag
                    v-if="chatContentMode === 'messages' && currentChatSession?.status === 'ARCHIVED'"
                    size="small"
                    effect="plain"
                >
                  已归档
                </el-tag>
              </div>
            </div>

            <template v-if="chatContentMode === 'sessions'">
              <div class="chat-session-manager">
                <div class="chat-session-toolbar">
                  <el-input
                      v-model.trim="chatSessionKeyword"
                      placeholder="搜索会话"
                      clearable
                      size="small"
                      @keyup.enter="searchChatSessions"
                      @clear="resetChatSessionSearch"
                  >
                    <template #prefix>
                      <el-icon><Search /></el-icon>
                    </template>
                  </el-input>
                  <el-select v-model="chatSessionStatus" size="small" class="chat-session-status" @change="searchChatSessions">
                    <el-option label="进行中" value="ACTIVE" />
                    <el-option label="已归档" value="ARCHIVED" />
                    <el-option label="全部" value="ALL" />
                  </el-select>
                </div>
                <div class="chat-session-manager-list">
                  <div
                      v-for="session in chatSessions"
                      :key="session.id"
                      :class="['chat-session-card', { active: String(activeChatSessionId?.value || '') === String(session.id) }]"
                  >
                    <button
                        type="button"
                        class="chat-session-card-main"
                        @click="selectSessionFromManager(session.id)"
                    >
                      <div class="chat-session-card-head">
                        <span class="chat-session-card-title">{{ session.title }}</span>
                        <el-tag v-if="session.status === 'ARCHIVED'" size="small" effect="plain">已归档</el-tag>
                      </div>
                      <div class="chat-session-card-meta">
                        {{ session.turnCount || 0 }}轮 · {{ formatChatHistoryTime(session.updatedAt) }}
                      </div>
                      <div class="chat-session-card-summary">
                        {{ session.summary || '暂无摘要，进入对话后可继续补充。' }}
                      </div>
                    </button>
                    <div class="chat-session-card-actions">
                      <button type="button" class="chat-session-card-icon" title="刷新摘要" @click.stop="refreshSessionFromManager(session)">
                        <el-icon><Refresh /></el-icon>
                      </button>
                      <button type="button" class="chat-session-card-icon" title="重命名" @click.stop="renameChatSession(session)">
                        <el-icon><Edit /></el-icon>
                      </button>
                      <button
                          type="button"
                          class="chat-session-card-icon"
                          :title="session.status === 'ARCHIVED' ? '恢复会话' : '归档会话'"
                          @click.stop="updateChatSessionStatus(session, session.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED')"
                      >
                        <el-icon><Management /></el-icon>
                      </button>
                      <button type="button" class="chat-session-card-icon danger" title="删除会话" @click.stop="deleteSessionFromManager(session)">
                        <el-icon><Close /></el-icon>
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div class="chat-conversation-shell">
                <div class="chat-thread-header chat-thread-header--inside">
                  <button
                      type="button"
                      class="chat-thread-toggle"
                      :title="'打开会话管理'"
                      :aria-label="'打开会话管理'"
                      @click="toggleChatContentMode"
                  >
                    <el-icon>
                      <ArrowLeftBold />
                    </el-icon>
                  </button>
                  <div class="chat-thread-title-wrap">
                    <div class="chat-thread-title">{{ currentChatSessionTitle }}</div>
                    <div class="chat-thread-subtitle">{{ currentChatSessionSubtitle }}</div>
                  </div>
                  <div class="chat-thread-actions">
                    <el-button
                        size="small"
                        plain
                        class="chat-thread-new-btn"
                        :loading="chatSessionLoading"
                        @click="createSessionAndOpen"
                    >
                      新建对话
                    </el-button>
                    <el-tag
                        v-if="currentChatSession?.status === 'ARCHIVED'"
                        size="small"
                        effect="plain"
                    >
                      已归档
                    </el-tag>
                  </div>
                </div>
                <div class="chat-conversation-main">
              <div v-if="activeBranchParentTurnMeta" class="chat-branch-banner">
                <span>当前将基于指定消息继续追问</span>
                <small>{{ activeBranchParentTurnMeta.preview || `Turn #${activeBranchParentTurnMeta.turnNo || activeBranchParentTurnMeta.turnId}` }}</small>
                <el-button size="small" text @click="clearActiveBranchParent">清除</el-button>
              </div>

              <div class="message-list" id="chatHistory" ref="chatHistoryRef">
                <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
                  <div class="avatar">
                    <img v-if="msg.role === 'system'" :src="chatQueryAvatar" alt="" aria-hidden="true" />
                    <img v-else :src="chatPeopleAvatar" alt="" aria-hidden="true" />
                  </div>
                  <div class="msg-content">
                    <div v-if="msg.isFollowUp && msg.followUpMeta" class="message-followup-ref">
                      <div class="message-followup-ref__label">
                        <span class="message-followup-ref__dot"></span>
                        <span>追问上下文</span>
                      </div>
                      <div class="message-followup-ref__content">
                        {{ msg.followUpMeta.preview || `Turn #${msg.followUpMeta.turnNo || msg.followUpMeta.parentTurnId}` }}
                      </div>
                      <div v-if="msg.followUpMeta.tableName" class="message-followup-ref__meta">
                        {{ msg.followUpMeta.source === 'history' ? '来自历史产物' : '来自当前对话' }} · {{ msg.followUpMeta.tableName }}
                      </div>
                    </div>
                    <div class="bubble" :class="{ 'bubble--thinking': msg.thinkingLogs?.length && msg.thinkingCollapsed === false }">
                      <span v-if="msg.thinkingLogs?.length && msg.thinkingCollapsed === false" class="thinking-spinner" aria-hidden="true">
                        <i v-for="spinnerIndex in 8" :key="spinnerIndex"></i>
                      </span>
                      <span>{{ msg.content }}</span>
                    </div>
                    <div v-if="msg.advancedAnalysis" class="advanced-dialog-entry">
                      <div class="advanced-dialog-entry__main">
                        <div class="advanced-dialog-entry__type">{{ advancedAnalysisTypeLabel(msg.advancedAnalysis.type) }}</div>
                        <div class="advanced-dialog-entry__title">{{ msg.advancedAnalysis.title }}</div>
                        <div class="advanced-dialog-entry__summary">{{ msg.advancedAnalysis.summary }}</div>
                        <div v-if="advancedRuleInfo(msg.advancedAnalysis).has" class="advanced-dialog-entry__rule">
                          <div>
                            <span>命中规则</span>
                            <strong>{{ advancedRuleInfo(msg.advancedAnalysis).ruleName || advancedRuleInfo(msg.advancedAnalysis).ruleCode }}</strong>
                          </div>
                          <div v-if="advancedRuleInfo(msg.advancedAnalysis).ruleCode">
                            <span>规则编码</span>
                            <strong>{{ advancedRuleInfo(msg.advancedAnalysis).ruleCode }}</strong>
                          </div>
                          <div v-if="advancedRuleInfo(msg.advancedAnalysis).scenarioType">
                            <span>推荐场景</span>
                            <strong>{{ advancedRuleInfo(msg.advancedAnalysis).scenarioLabel }}</strong>
                          </div>
                          <div v-if="advancedRuleInfo(msg.advancedAnalysis).explain" class="advanced-dialog-entry__rule-explain">
                            <span>推荐说明</span>
                            <strong>{{ advancedRuleInfo(msg.advancedAnalysis).explain }}</strong>
                          </div>
                        </div>
                      </div>
                      <div class="advanced-dialog-entry__actions">
                        <el-tag size="small" effect="light" :type="msg.advancedAnalysis.status === '模拟生成' ? 'warning' : 'success'">
                          {{ msg.advancedAnalysis.status || '已生成' }}
                        </el-tag>
                        <el-button size="small" type="primary" plain @click="openAdvancedAnalysisDialog(msg.advancedAnalysis)">
                          查看详情
                        </el-button>
                      </div>
                    </div>
                    <div v-if="msg.fieldBindingResults?.length" class="field-binding-card">
                      <div class="field-binding-card__header">{{ msg.fieldBindingTitle || '字段变更结果' }}</div>
                      <div
                        v-for="(item, bindingIndex) in msg.fieldBindingResults"
                        :key="`${index}-binding-${bindingIndex}`"
                        class="field-binding-card__item"
                      >
                        <div class="field-binding-card__label">{{ item.label || item.name }}</div>
                        <div class="field-binding-card__arrow">→</div>
                        <div class="field-binding-card__field">
                          {{ formatFieldBindingResultValue(item) }}
                        </div>
                      </div>
                    </div>
                    <div v-if="msg.alertRuleDraft" class="alert-draft-card">
                      <div class="alert-draft-card__header">
                        <div>
                          <div class="alert-draft-card__eyebrow">预警规则草稿</div>
                          <div class="alert-draft-card__title">{{ formatChatAlertDraftTitle(msg.alertRuleDraft) }}</div>
                        </div>
                        <el-tag size="small" type="warning" effect="light">待确认</el-tag>
                      </div>
                      <div class="alert-draft-card__grid">
                        <div>
                          <span>指标</span>
                          <strong>{{ fieldLabel(msg.alertRuleDraft.metricField) || msg.alertRuleDraft.metricField || '待确认' }}</strong>
                        </div>
                        <div>
                          <span>条件</span>
                          <strong>{{ alertOperatorLabel(msg.alertRuleDraft.operator) }} {{ formatAdvancedNumber(msg.alertRuleDraft.threshold) }}</strong>
                        </div>
                        <div>
                          <span>时间字段</span>
                          <strong>{{ fieldLabel(msg.alertRuleDraft.timeField) || msg.alertRuleDraft.timeField || '待确认' }}</strong>
                        </div>
                        <div>
                          <span>通知渠道</span>
                          <strong>{{ formatAlertChannel(msg.alertRuleDraft.channels) }}</strong>
                        </div>
                      </div>
                      <div class="alert-draft-card__footer">
                        <div class="alert-draft-card__hint">该规则尚未正式创建，需要确认后再保存到预警规则。</div>
                        <el-button
                          class="alert-draft-card__confirm"
                          size="small"
                          :loading="savingAlertDraftKey === alertDraftKey(msg.alertRuleDraft)"
                          @click="confirmChatAlertDraft(msg.alertRuleDraft, msg)"
                        >
                          确认创建
                        </el-button>
                      </div>
                    </div>
                    <div v-if="alertEventRowsForMessage(msg).length" class="alert-event-table-card">
                      <div class="alert-event-table-card__header">
                        <div>
                          <div class="alert-event-table-card__eyebrow">预警事件</div>
                          <div class="alert-event-table-card__title">最近触发的报警记录</div>
                        </div>
                        <el-tag size="small" type="warning" effect="light">
                          {{ alertEventRowsForMessage(msg).length }} 条
                        </el-tag>
                      </div>
                      <el-table
                          class="alert-event-table"
                          :data="visibleAlertEventRows(msg)"
                          size="small"
                          max-height="260"
                          border
                      >
                        <el-table-column prop="id" label="ID" width="86" />
                        <el-table-column label="规则名" min-width="170">
                          <template #default="{ row }">
                            <span class="alert-event-table__rule">{{ alertEventRuleName(row) }}</span>
                          </template>
                        </el-table-column>
                        <el-table-column label="状态" width="84">
                          <template #default="{ row }">
                            <el-tag size="small" :type="alertEventStatusTagType(row.status)" effect="light">
                              {{ alertEventStatusLabel(row.status) }}
                            </el-tag>
                          </template>
                        </el-table-column>
                        <el-table-column label="触发时间" min-width="132">
                          <template #default="{ row }">
                            {{ row.createdAt || row.bucketName || '-' }}
                          </template>
                        </el-table-column>
                        <el-table-column label="快照" width="96" fixed="right">
                          <template #default="{ row }">
                            <el-button
                                class="alert-event-table__snapshot-btn"
                                size="small"
                                type="primary"
                                link
                                @click="openAlertEventSnapshot(row, msg)"
                            >
                              <el-icon><View /></el-icon>
                              查看
                            </el-button>
                          </template>
                        </el-table-column>
                      </el-table>
                      <div v-if="alertEventRowsForMessage(msg).length > 10" class="alert-event-table-card__footer">
                        已显示最近 10 条，可使用表格中的 ID 继续追问触发原因或处理状态。
                      </div>
                    </div>
                    <div v-if="hasMultiStepPlan(msg)" class="multi-step-card">
                      <div class="multi-step-card__header">
                        <div>
                          <div class="multi-step-card__eyebrow">多步骤任务编排</div>
                          <div class="multi-step-card__title">{{ multiStepActionChain(msg) }}</div>
                        </div>
                        <el-tag size="small" :type="multiStepSummaryTagType(msg)" effect="light">
                          {{ multiStepSummaryLabel(msg) }}
                        </el-tag>
                      </div>
                      <div v-if="multiStepMissingSlots(msg).length" class="multi-step-card__clarify">
                        需要补充：{{ multiStepMissingSlots(msg).join('、') }}
                      </div>
                      <div class="multi-step-card__summary">
                        <span>共 {{ multiStepSummaryValue(msg, 'total') }} 步</span>
                        <span>完成 {{ multiStepSummaryValue(msg, 'completed') }}</span>
                        <span v-if="multiStepSummaryValue(msg, 'needsConfirmation')">待确认 {{ multiStepSummaryValue(msg, 'needsConfirmation') }}</span>
                        <span v-if="multiStepSummaryValue(msg, 'failed')">失败 {{ multiStepSummaryValue(msg, 'failed') }}</span>
                        <span v-if="multiStepSummaryValue(msg, 'skipped')">跳过 {{ multiStepSummaryValue(msg, 'skipped') }}</span>
                      </div>
                      <div class="multi-step-card__steps">
                        <div
                          v-for="(step, stepIndex) in normalizedMultiStepActions(msg)"
                          :key="step.id || `${index}-multi-step-${stepIndex}`"
                          class="multi-step-card__step"
                          :class="`multi-step-card__step--${multiStepStatusClass(step.status)}`"
                        >
                          <div class="multi-step-card__step-index">{{ stepIndex + 1 }}</div>
                          <div class="multi-step-card__step-main">
                            <div class="multi-step-card__step-head">
                              <strong>{{ multiStepActionTypeLabel(step.type) }}</strong>
                              <span v-if="formatMultiStepConfidence(step.confidence)" class="multi-step-card__confidence">
                                置信度 {{ formatMultiStepConfidence(step.confidence) }}
                              </span>
                              <el-tag size="small" :type="multiStepStatusTagType(step.status)" effect="light">
                                {{ multiStepStatusLabel(step.status) }}
                              </el-tag>
                            </div>
                            <div v-if="step.question" class="multi-step-card__question">{{ step.question }}</div>
                            <div v-if="step.dependsOn?.length" class="multi-step-card__dependency">
                              依赖步骤：{{ step.dependsOn.join('、') }}
                            </div>
                            <div v-if="step.message" class="multi-step-card__message">{{ step.message }}</div>
                          </div>
                        </div>
                      </div>
                    </div>
                    <details v-if="msg.thinkingLogs?.length" class="thinking-details" :open="msg.thinkingCollapsed === false">
                      <summary>查看思考过程（{{ msg.thinkingLogs.length }}步）</summary>
                      <ol class="thinking-list">
                        <li v-for="(line, lineIndex) in msg.thinkingLogs" :key="`${index}-${lineIndex}`">
                          {{ line }}
                        </li>
                      </ol>
                    </details>
                    <div v-if="msg.sql" class="sql-block">
                      <div class="sql-head">
                        <div class="sql-title">生成的 SQL</div>
                        <el-button size="small" text type="primary" @click="copySqlToClipboard(msg.sql)">复制</el-button>
                      </div>
                      <pre class="sql-code">{{ msg.sql }}</pre>
                    </div>
                    <div v-if="msg.role === 'system' || (speechSupported && msg.content)" class="bubble-voice-action">
                      <button
                          v-if="isMessageChartRestorable(msg)"
                          type="button"
                          class="bubble-voice-btn"
                          title="查看本轮对话图表"
                          aria-label="查看本轮对话图表"
                          @click="openHistoricalAnalysis(msg)"
                      >
                        <el-icon class="bubble-voice-graphic" aria-hidden="true"><View /></el-icon>
                      </button>
                      <button
                          v-if="msg.turnId && msg.role === 'system'"
                          type="button"
                          class="bubble-voice-btn"
                          title="基于此消息继续追问"
                          aria-label="基于此消息继续追问"
                          @click="setActiveBranchParent(msg)"
                      >
                        <el-icon class="bubble-voice-graphic" aria-hidden="true"><Share /></el-icon>
                      </button>
                      <button
                          v-if="speechSupported && msg.content"
                          type="button"
                          class="bubble-voice-btn"
                          :title="speaking && !speechPaused ? '暂停播报' : (speaking && speechPaused ? '继续播报' : '播报当前气泡内容')"
                          @click="speaking ? toggleVoicePlayback() : speakChatBubble(msg)"
                      >
                        <span class="bubble-voice-icon" aria-hidden="true">{{ speaking && !speechPaused ? '⏸' : (speaking && speechPaused ? '▶' : '🔊') }}</span>
                      </button>
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="activeBranchParentTurnMeta" class="chat-followup-banner">
                <div class="chat-followup-banner__main">
                  <div class="chat-followup-banner__eyebrow">继续追问</div>
                  <div class="chat-followup-banner__title">
                    {{ activeBranchParentTurnMeta.preview || `Turn #${activeBranchParentTurnMeta.turnNo || activeBranchParentTurnMeta.turnId}` }}
                  </div>
                  <div class="chat-followup-banner__meta">
                    {{ activeBranchParentTurnMeta.source === 'history' ? '来自历史产物' : '来自当前对话消息' }}
                    <span v-if="activeBranchParentTurnMeta.tableName"> · {{ activeBranchParentTurnMeta.tableName }}</span>
                  </div>
                </div>
                <el-button size="small" text @click="clearActiveBranchParent">取消追问</el-button>
              </div>
                </div>

              <div class="ask-bar">
                <el-input
                    v-model="question"
                    placeholder="试试问我：按省份统计销售额、按日期看趋势..."
                    :readonly="loading"
                    @keyup.enter="!loading && sendChatQuestion()"
                >
                  <template #prefix>
                    <span v-if="loading" class="ask-input-loading" aria-label="思考中">
                      <i v-for="spinnerIndex in 8" :key="spinnerIndex"></i>
                    </span>
                  </template>
                  <template #suffix>
                    <span class="ask-input-actions">
                      <el-popover
                          v-if="chatModelOptions?.length"
                          v-model:visible="chatModelPickerVisible"
                          trigger="click"
                          placement="top-start"
                          :width="236"
                          :teleported="false"
                          popper-class="chat-model-menu-popper"
                      >
                        <template #reference>
                          <button
                              type="button"
                              :class="['chat-model-trigger', { active: chatModelPickerVisible }]"
                              title="切换模型"
                              aria-label="切换模型"
                          >
                            <span>{{ currentChatModelShortLabel }}</span>
                            <el-icon><ArrowDown /></el-icon>
                          </button>
                        </template>
                        <div class="chat-model-menu">
                          <div class="chat-model-menu__group">模型</div>
                          <button
                              v-for="model in chatModelOptions"
                              :key="model.id"
                              type="button"
                              :class="['chat-model-menu__item', { active: String(model.id) === String(selectedChatModelId) }]"
                              @click="selectChatModel(model.id)"
                          >
                            <span>{{ formatChatModelLabel(model) }}</span>
                            <span v-if="String(model.id) === String(selectedChatModelId)" class="chat-model-menu__check">✓</span>
                          </button>
                        </div>
                      </el-popover>
                      <button
                          type="button"
                          :class="['ask-input-icon-btn', { active: listening }]"
                          :disabled="!recognitionSupported"
                          :title="listening ? '停止语音输入' : '开始语音输入'"
                          :aria-label="listening ? '停止语音输入' : '开始语音输入'"
                          @click="listening ? stopVoiceQuestionInput() : startVoiceQuestionInput()"
                      >
                        <el-icon><Microphone /></el-icon>
                      </button>
                      <button
                          type="button"
                          class="ask-input-icon-btn"
                          title="语音设置"
                          aria-label="语音设置"
                          @click="voicePanelVisible = true"
                      >
                        <el-icon><Setting /></el-icon>
                      </button>
                      <button
                          v-if="!loading"
                          type="button"
                          class="ask-input-send-btn"
                          title="发送分析"
                          aria-label="发送分析"
                          @click="sendChatQuestion"
                      >
                        <el-icon><Promotion /></el-icon>
                      </button>
                      <button
                          v-else
                          type="button"
                          class="ask-input-stop-btn"
                          title="停止生成"
                          aria-label="停止生成"
                          @click="stopQuestionGeneration"
                      >
                        <span class="stop-btn__disc">
                          <span class="stop-btn__square"></span>
                        </span>
                      </button>
                    </span>
                  </template>
                </el-input>
              </div>
              </div>
            </template>
          </div>

          <div class="panel chart-panel">
            <div class="panel-header">
              <div class="chart-panel-title">
                <span class="chart-panel-icon" aria-hidden="true">
                  <el-icon><DataAnalysis /></el-icon>
                </span>
                <span class="chart-panel-title-text">
                  <h2>智能可视化呈现</h2>
                  <p>AI 将理解您的意图并推荐最合适的可视化类型</p>
                </span>
              </div>
              <div class="chart-actions">
                <el-button
                    v-if="canRegenerateLastAnalysis"
                    size="small"
                    type="primary"
                    plain
                    class="chart-action-btn chart-action-btn--primary"
                    :disabled="loading || isStreaming"
                    @click="regenerateLastAnalysis"
                >
                  <el-icon><Refresh /></el-icon>
                  <span>重新生成</span>
                </el-button>
                <el-button
                    v-if="lastAnalysis?.data?.length && !isLastAnalysisTable"
                    size="small"
                    class="chart-action-btn"
                    @click="exportChartAsImage"
                >
                  <el-icon><Document /></el-icon>
                  <span>导出图片</span>
                </el-button>
                <el-button
                    v-if="canPinLastAnalysis"
                    size="small"
                    type="success"
                    plain
                    class="chart-action-btn"
                    :disabled="loading || isStreaming"
                    @click="openPinDialog"
                >
                  <el-icon><Management /></el-icon>
                  钉入看板
                </el-button>
              </div>
            </div>

            <div class="chart-panel-scroll">
            <div v-if="lastAnalysis && !isLastAnalysisTable" class="chart-control-bar">
              <el-select
                  v-if="currentChartType"
                  :model-value="currentChartType"
                  class="chart-control-select chart-type-select"
                  popper-class="chart-control-select-popper"
                  @change="changeLastAnalysisChartType"
              >
                <template #prefix>
                  <el-icon><TrendCharts /></el-icon>
                  <span class="chart-control-select-label">图表类型：</span>
                </template>
                <template #label="{ label, value }">
                  <span class="chart-type-selected-label">
                    <span>{{ label }}</span>
                    <span v-if="isAiRecommendedChartType(value)" class="chart-type-recommend-pill chart-type-recommend-pill--current">AI推荐</span>
                  </span>
                </template>
                <el-option
                    v-for="option in chartTypeSwitchOptions"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                >
                  <span class="chart-type-option">
                    <span>{{ option.label }}</span>
                    <span v-if="isAiRecommendedChartType(option.value)" class="chart-type-recommend-pill">AI推荐</span>
                  </span>
                </el-option>
              </el-select>
              <el-select
                  v-if="!isLastAnalysisMetric"
                  v-model="chartSortMode"
                  class="chart-control-select"
                  popper-class="chart-control-select-popper"
                  @change="() => lastAnalysis?.data?.length && renderChart(lastAnalysis.data, currentChartType)"
              >
                <template #prefix>
                  <el-icon><Sort /></el-icon>
                  <span class="chart-control-select-label">排序：</span>
                </template>
                <el-option label="按名称排序" value="name" />
                <el-option label="按数值降序" value="desc" />
                <el-option label="按数值升序" value="asc" />
              </el-select>
            </div>

            <div v-if="!lastAnalysis" class="chart-empty-state">
              <img class="chart-empty-illustration" :src="noImageIllustration" alt="暂无图表" />
              <h3>暂未生成图表</h3>
              <p>请先在左侧输入分析问题，系统将为你生成图表与结果说明</p>
              <div class="chart-empty-guide">
                <div class="chart-empty-guide-title">
                  <el-icon><QuestionFilled /></el-icon>
                  <span>你可以这样开始</span>
                </div>
                <div class="chart-empty-guide-steps">
                  <div class="chart-empty-guide-step">
                    <span class="chart-empty-guide-index">1</span>
                    <span class="chart-empty-guide-icon chart-empty-guide-icon--database" aria-hidden="true">
                      <svg viewBox="0 0 24 24" focusable="false">
                        <ellipse cx="12" cy="5" rx="7" ry="3"></ellipse>
                        <path d="M5 5v6c0 1.66 3.13 3 7 3s7-1.34 7-3V5"></path>
                        <path d="M5 11v6c0 1.66 3.13 3 7 3s7-1.34 7-3v-6"></path>
                      </svg>
                    </span>
                    <span class="chart-empty-guide-copy">
                      <strong>选择数据源</strong>
                      <small>选择要分析的数据范围</small>
                    </span>
                  </div>
                  <div class="chart-empty-guide-step">
                    <span class="chart-empty-guide-index">2</span>
                    <span class="chart-empty-guide-icon">
                      <el-icon><Box /></el-icon>
                    </span>
                    <span class="chart-empty-guide-copy">
                      <strong>选择业务模型</strong>
                      <small>选择合适的业务模型</small>
                    </span>
                  </div>
                  <div class="chart-empty-guide-step">
                    <span class="chart-empty-guide-index">3</span>
                    <span class="chart-empty-guide-icon">
                      <el-icon><Edit /></el-icon>
                    </span>
                    <span class="chart-empty-guide-copy">
                      <strong>输入分析问题</strong>
                      <small>用自然语言描述问题</small>
                    </span>
                  </div>
                </div>
              </div>
            </div>
            <div v-show="lastAnalysis && !isLastAnalysisTable && !isLastAnalysisMetric" id="echarts-container" class="chart-canvas" @mousedown.stop @touchstart.stop @pointerdown.stop></div>
            <div v-if="lastAnalysis && isLastAnalysisMetric" id="analysis-metric-card" class="analysis-metric-card">
              <div class="analysis-metric-main">
                <div class="analysis-metric-value">
                  <span>{{ lastAnalysisMetricDisplay.value }}</span>
                  <small v-if="lastAnalysisMetricDisplay.unit">{{ lastAnalysisMetricDisplay.unit }}</small>
                </div>
                <div class="analysis-metric-label">{{ lastAnalysisMetricDisplay.label }}</div>
              </div>
              <div
                  v-if="lastAnalysisMetricDisplay.compareValue || lastAnalysisMetricDisplay.trend"
                  class="analysis-metric-compare"
              >
                <span
                    v-if="lastAnalysisMetricDisplay.trend"
                    :class="['analysis-metric-trend', `analysis-metric-trend--${lastAnalysisMetricDisplay.trend}`]"
                >
                  {{ metricTrendText(lastAnalysisMetricDisplay.trend) }}
                </span>
                <span v-if="lastAnalysisMetricDisplay.compareValue">
                  {{ lastAnalysisMetricDisplay.compareLabel }} {{ lastAnalysisMetricDisplay.compareValue }}
                </span>
              </div>
              <div v-if="lastAnalysisMetricDisplay.note" class="analysis-metric-note">
                {{ lastAnalysisMetricDisplay.note }}
              </div>
            </div>
            <div v-if="lastAnalysis && isLastAnalysisTable" class="analysis-table-wrap">
              <el-table
                  :data="lastAnalysisTableRows"
                  border
                  stripe
                  height="360"
                  table-layout="fixed"
                  empty-text="暂无明细数据"
              >
                <el-table-column
                    v-for="column in lastAnalysisTableColumns"
                    :key="column.prop"
                    :prop="column.prop"
                    :label="column.label"
                    min-width="130"
                    show-overflow-tooltip
                />
              </el-table>
            </div>

            <el-descriptions v-if="lastAnalysis" :column="2" border class="analysis-meta">
              <el-descriptions-item label="数据表">{{ lastAnalysis.tableName }}</el-descriptions-item>
              <el-descriptions-item label="图表">{{ chartTypeLabel }}</el-descriptions-item>
              <el-descriptions-item label="维度">{{ lastAnalysis.fieldMapping?.dimension }}</el-descriptions-item>
              <el-descriptions-item label="指标">{{ lastAnalysis.fieldMapping?.metric }}</el-descriptions-item>
              <el-descriptions-item label="SQL风险">
                <el-tag :type="riskTagType(lastAnalysis.riskLevel)" size="small">
                  {{ lastAnalysis.riskLevel }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="审计说明">{{ lastAnalysis.riskReason }}</el-descriptions-item>
              <el-descriptions-item v-if="analysisSensitiveFields.length" label="敏感字段" :span="2">
                <div class="audit-sensitive-list">
                  <el-tag
                      v-for="field in analysisSensitiveFields"
                      :key="field"
                      size="small"
                      type="warning"
                      effect="light"
                  >
                    {{ field }}
                  </el-tag>
                </div>
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRuleName || lastAnalysis.chartRuleCode" label="命中规则">
                <el-tag type="primary" size="small">{{ lastAnalysis.chartRuleName || lastAnalysis.chartRuleCode }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRuleCode" label="规则编码">
                {{ lastAnalysis.chartRuleCode }}
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartScenarioType" label="推荐场景">
                {{ chartRecommendationScenarioLabel(lastAnalysis.chartScenarioType) }}
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRecommendationStatus" label="推荐状态">
                <el-tag :type="String(lastAnalysis.chartRecommendationStatus).toUpperCase() === 'FALLBACK' ? 'warning' : 'success'" size="small">
                  {{ formatChartRecommendationStatus(lastAnalysis.chartRecommendationStatus) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRecommendationExplain" label="推荐说明" :span="2">
                <span
                  class="analysis-meta-long-text"
                  :title="formatChartRecommendationExplain(lastAnalysis.chartRecommendationExplain, lastAnalysis)"
                >
                  {{ formatChartRecommendationExplain(lastAnalysis.chartRecommendationExplain, lastAnalysis) }}
                </span>
              </el-descriptions-item>
            </el-descriptions>

            <CollabChatInsightPanel
              v-if="lastAnalysis"
              :chart-id="lastAnalysisChartLinkId"
              :dashboard-id="pinDashboardId"
            />

            <section
                v-if="lastAnalysis || diagnosisPreviewHasReport"
                class="diagnosis-preview-card"
                :class="{ 'is-pending': !diagnosisPreviewHasReport }"
                aria-label="最新诊断报告预览"
            >
              <div class="diagnosis-preview-head">
                <div class="diagnosis-preview-title-wrap">
                  <span class="diagnosis-preview-icon">
                    <el-icon><Document /></el-icon>
                  </span>
                  <div class="diagnosis-preview-title-main">
                    <div class="diagnosis-preview-kicker">{{ diagnosisPreviewKicker }}</div>
                    <h3>{{ diagnosisPreviewTitle }}</h3>
                  </div>
                </div>
                <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="diagnosisPreviewHasReport ? View : Document"
                    :loading="!diagnosisPreviewHasReport && diagnosisLoading"
                    :disabled="!diagnosisPreviewHasReport && !diagnosisPreviewCanGenerate"
                    class="diagnosis-preview-action"
                    @click="handleDiagnosisPreviewAction"
                >
                  {{ diagnosisPreviewActionText }}
                </el-button>
              </div>
              <div class="diagnosis-preview-body">
                <div class="diagnosis-preview-meta">
                  <span v-if="diagnosisPreviewTable" class="diagnosis-preview-meta-item">
                    <el-icon><DataAnalysis /></el-icon>
                    {{ diagnosisPreviewTable }}
                  </span>
                  <span v-if="diagnosisPreviewMetric" class="diagnosis-preview-meta-item">
                    指标：{{ diagnosisPreviewMetric }}
                  </span>
                  <span class="diagnosis-preview-meta-item">
                    <el-icon><Calendar /></el-icon>
                    {{ diagnosisPreviewCreatedAt }}
                  </span>
                </div>
                <div class="diagnosis-preview-summary">
                  <span>摘要</span>
                  <p>{{ diagnosisPreviewSummary }}</p>
                </div>
                <div class="diagnosis-preview-stats">
                  <div v-for="item in diagnosisPreviewStats" :key="item.label" class="diagnosis-preview-stat">
                    <el-icon>
                      <component :is="item.icon" />
                    </el-icon>
                    <div>
                      <span>{{ item.label }}</span>
                      <strong>{{ item.value }}</strong>
                    </div>
                  </div>
                </div>
              </div>
            </section>
            </div>

          </div>
          <div v-if="lastAnalysis" class="panel graph-context-panel">
            <div class="panel-header">
              <div>
                <h2 class="semantic-evidence-title">
                  <span>{{ semanticEvidencePanelTitle }}</span>
                  <el-tooltip
                      content="当本次查询命中业务模型、GraphRAG、字段映射或 SQL 引用依据时展示精确语义依据；未形成精确命中但有图谱候选时展示 GraphRAG 候选上下文；两者都没有时显示未命中提示。"
                      placement="top"
                      :show-after="200"
                  >
                    <el-icon class="semantic-evidence-help" aria-label="语义依据说明">
                      <QuestionFilled />
                    </el-icon>
                  </el-tooltip>
                </h2>
                <p>{{ semanticEvidencePanelDescription }}</p>
              </div>
            </div>
            <div
                v-if="semanticEvidenceItems.length"
                class="semantic-evidence-hit-note"
            >
              实际命中：以下 {{ semanticEvidenceItems.length }} 项语义依据参与了本次字段识别、指标口径或 SQL 生成；下方知识图谱为相关召回关系，用于辅助核验。
            </div>
            <div v-if="semanticEvidenceItems.length" class="graph-context-list semantic-evidence-hit-list">
              <div
                  v-for="(item, index) in semanticEvidenceItems"
                  :key="item.field || item.label || index"
                  class="graph-context-item semantic-evidence-hit-card"
              >
                <div class="graph-context-meta">
                  <div class="graph-context-name">{{ formatSemanticEvidenceTitle(item) }}</div>
                  <div v-if="formatSemanticEvidenceSource(item)" class="graph-context-sub">
                    {{ formatSemanticEvidenceSource(item) }}
                  </div>
                </div>
                <div class="graph-context-content">{{ formatSemanticEvidenceContent(item) }}</div>
              </div>
            </div>
            <div v-if="semanticKnowledgeGraphVisible" class="semantic-kg-card">
              <div class="semantic-kg-card__head">
                <div>
                  <strong>知识图谱关系</strong>
                  <span>{{ semanticKnowledgeGraphData.sourceLabel }}，用于展示相关节点关系</span>
                </div>
                <small>{{ semanticKnowledgeGraphData.nodes.length }} 个节点 / {{ semanticKnowledgeGraphData.edges.length }} 条关系</small>
              </div>
              <div ref="semanticKnowledgeGraphRef" class="semantic-kg-chart" aria-label="本次语义依据知识图谱"></div>
            </div>
            <el-collapse v-if="!semanticEvidenceItems.length && graphContextFallbackItems.length" class="graph-context-collapse">
              <el-collapse-item name="graph-context">
                <template #title>
                  <span class="graph-context-collapse-title">
                    GraphRAG 候选上下文
                    <small>已保留原始候选，默认折叠展示</small>
                  </span>
                </template>
                <div class="graph-context-list">
                  <div
                      v-for="(item, index) in graphContextFallbackItems"
                      :key="item.nodeKey || item.sourceId || item.label || index"
                      class="graph-context-item graph-context-item--muted"
                  >
                    <div class="graph-context-meta">
                      <div class="graph-context-name">{{ formatGraphContextTitle(item) }}</div>
                      <div v-if="formatGraphContextSource(item)" class="graph-context-sub">
                        {{ formatGraphContextSource(item) }}
                      </div>
                    </div>
                    <div class="graph-context-content">{{ formatGraphContextContent(item) }}</div>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
            <el-empty
                v-if="!semanticEvidenceItems.length && !graphContextFallbackItems.length && !semanticKnowledgeGraphVisible"
                class="semantic-evidence-empty"
                description="本次未命中可解释语义依据"
                :image-size="72"
            />
          </div>
          <el-dialog v-model="pinDialogVisible" title="钉入我的看板" width="520px" append-to-body>
            <p class="pin-dialog-hint">仅显示您创建或另存且未发布的看板；已发布看板不可钉入，请先另存为副本。</p>
            <el-form label-position="top">
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
              <el-button @click="pinDialogVisible = false">取消</el-button>
              <el-button type="primary" :loading="pinning" @click="pinChartToDashboard">确认钉入</el-button>
            </template>
          </el-dialog>
          <el-drawer
              v-model="businessDictionaryPanelVisible"
              title="业务字典 + 业务公式维护"
              size="84%"
              destroy-on-close
              @open="refreshBusinessDictionaryPanel"
          >
            <BusinessDictionaryView
              :focus-model-id="businessDictionaryFocusModelId"
              :show-title="false"
              :auto-rename-on-duplicate-create="true"
              :use-create-dialog="true"
            />
          </el-drawer>
          <el-drawer
              v-model="voicePanelVisible"
              title="语音交互设置"
              size="420px"
              destroy-on-close
          >
            <div class="voice-settings">
              <el-alert
                  :title="voiceCapabilityText"
                  :type="recognitionSupported || speechSupported ? 'success' : 'warning'"
                  :closable="false"
                  show-icon
              />
              <el-form label-position="top">
                <el-form-item label="识别语种 / 方言适配">
                  <el-select v-model="recognitionLocale" class="full-width">
                    <el-option
                        v-for="option in voiceLocaleOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报语种 / 方言适配">
                  <el-select v-model="voiceLocale" class="full-width">
                    <el-option
                        v-for="option in voiceLocaleOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报音色">
                  <el-select v-model="selectedVoiceGender" class="full-width">
                    <el-option
                        v-for="option in localVoiceGenderOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="播报语速">
                  <el-slider v-model="speechRate" :min="0.6" :max="1.4" :step="0.1" show-input />
                </el-form-item>
                <el-form-item label="播报音量">
                  <el-slider v-model="speechVolume" :min="0" :max="1" :step="0.05" show-input />
                </el-form-item>
                <el-form-item>
                  <el-switch
                      v-model="autoSpeakConclusion"
                      active-text="查询完成后自动播报结论"
                      inactive-text="手动触发播报"
                  />
                </el-form-item>
                <el-form-item>
                  <el-switch
                      v-model="autoSendAfterRecognize"
                      active-text="识别结束后自动发起查询"
                      inactive-text="识别后手动确认"
                  />
                </el-form-item>
                <el-form-item label="实时听写预览">
                  <div class="voice-preview-box">
                    {{ [finalTranscript, interimTranscript].filter(Boolean).join(' ') || '开始语音输入后，这里会显示识别结果。' }}
                  </div>
                </el-form-item>
              </el-form>
              <div class="voice-history">
                <div class="voice-history__head">
                  <span>最近语音记录</span>
                  <el-button text size="small" @click="clearVoiceHistory">清空</el-button>
                </div>
                <div v-if="voiceHistory.length" class="voice-history__list">
                  <button
                      v-for="item in voiceHistory"
                      :key="`${item.createdAt}-${item.text}`"
                      type="button"
                      class="voice-history__item"
                      @click="question = normalizeVoiceQuestion(item.text)"
                  >
                    <span class="voice-history__text">{{ item.text }}</span>
                    <span class="voice-history__meta">{{ item.locale }} · {{ item.createdAt.slice(5, 16).replace('T', ' ') }}</span>
                  </button>
                </div>
                <div v-else class="voice-history__empty">暂无语音记录</div>
              </div>
            </div>
          </el-drawer>
          <el-drawer
              v-model="historyDrawerVisible"
              title="历史产物"
              size="72%"
              class="history-product-drawer"
              destroy-on-close
          >
            <div class="history-drawer">
              <div class="history-toolbar">
                <div class="history-toolbar__primary">
                  <el-input
                      v-model.trim="recentChatQueryKeyword"
                      class="history-toolbar__keyword"
                      placeholder="搜索问题、SQL、表名"
                      clearable
                      @keyup.enter="searchRecentChatQueries"
                      @clear="resetHistoryKeyword"
                  >
                    <template #prefix>
                      <el-icon><Search /></el-icon>
                    </template>
                  </el-input>
                  <el-select
                      v-model="recentChatQueryTableName"
                      class="history-toolbar__table"
                      clearable
                      filterable
                      placeholder="数据源"
                      @change="syncHistorySearch"
                  >
                    <el-option
                        v-for="table in historyTableOptions"
                        :key="table.tableName"
                        :label="formatTableOptionLabel(table)"
                        :value="table.tableName"
                    />
                  </el-select>
                  <el-select
                      v-model="recentChatQueryChartType"
                      class="history-toolbar__chart"
                      clearable
                      placeholder="图表类型"
                      @change="syncHistorySearch"
                  >
                    <el-option label="柱状图" value="bar" />
                    <el-option label="折线图" value="line" />
                    <el-option label="饼图" value="pie" />
                    <el-option label="雷达图" value="radar" />
                    <el-option label="散点图" value="scatter" />
                    <el-option label="指标卡" value="metric" />
                    <el-option label="地图" value="map" />
                    <el-option label="表格" value="table" />
                    <el-option label="智能预警" value="alert" />
                  </el-select>
                  <el-select
                      v-model="recentChatQueryRiskLevel"
                      class="history-toolbar__risk"
                      clearable
                      placeholder="风险等级"
                      @change="syncHistorySearch"
                  >
                    <el-option label="安全" value="SAFE" />
                    <el-option label="预警" value="WARN" />
                    <el-option label="拦截" value="BLOCKED" />
                  </el-select>
                  <el-select
                      v-model="recentChatQueryExecutionStatus"
                      class="history-toolbar__status"
                      clearable
                      placeholder="执行状态"
                      @change="syncHistorySearch"
                  >
                    <el-option label="成功" value="SUCCESS" />
                    <el-option label="失败" value="FAILED" />
                    <el-option label="取消" value="CANCELLED" />
                  </el-select>
                </div>
                <div class="history-toolbar__secondary">
                  <el-date-picker
                      v-model="recentChatQueryDateRange"
                      class="history-toolbar__date"
                      type="daterange"
                      range-separator="至"
                      start-placeholder="开始日期"
                      end-placeholder="结束日期"
                      value-format="YYYY-MM-DD"
                      @change="syncHistorySearch"
                  />
                  <div class="history-toolbar__quick-range">
                    <el-button
                        v-for="option in historyQuickDateOptions"
                        :key="option.value || 'all'"
                        :class="{ 'is-active': historyQuickDateRange === option.value }"
                        plain
                        @click="applyHistoryQuickDateRange(option.value)"
                    >
                      {{ option.label }}
                    </el-button>
                  </div>
                  <div class="history-toolbar__sort">
                    <el-button plain @click="toggleHistorySortDirection">
                      <el-icon><Sort /></el-icon>
                      {{ recentChatQuerySortDirection === 'ASC' ? '时间正序' : '时间倒序' }}
                      <el-icon><ArrowDown /></el-icon>
                    </el-button>
                  </div>
                  <div class="history-toolbar__actions">
                    <el-button type="primary" @click="searchRecentChatQueries">
                      <el-icon><Search /></el-icon>
                      <span>搜索</span>
                    </el-button>
                    <el-button plain @click="resetHistoryKeyword">
                      <el-icon><Refresh /></el-icon>
                      <span>重置</span>
                    </el-button>
                  </div>
                </div>
              </div>

              <div class="history-summary">
                <span>共 {{ recentChatQueryTotal }} 条</span>
                <el-button size="small" plain @click="advancedHistoryVisible = true">高级分析记录</el-button>
                <span v-if="recentChatQueryError" class="history-summary__error">{{ recentChatQueryError }}</span>
              </div>

              <div class="history-content" v-loading="recentChatQueryLoading">
                <div v-if="recentChatQueries?.length" class="history-list">
                  <button
                      v-for="entry in recentChatQueries"
                      :key="entry.id"
                      type="button"
                      :class="['history-card', { 'is-active': String(selectedHistoryId || '') === String(entry.id || '') }]"
                      @click="selectHistoryEntry(entry)"
                  >
                    <div class="history-card__head">
                      <div class="history-card__title-wrap">
                        <div class="history-card__title">{{ entry.question || '未命名查询' }}</div>
                      </div>
                      <el-tag size="small" effect="light" class="history-tag history-tag--risk" :type="riskTagType(entry.riskLevel)">
                        {{ entry.riskLevel || 'SAFE' }}
                      </el-tag>
                    </div>
                    <div class="history-card__meta">
                      <span class="history-card__meta-item">{{ entry.tableName || '未指定数据源' }}</span>
                      <span class="history-card__meta-item">{{ historyChartTypeLabel(entry.chartType) }}</span>
                      <span class="history-card__meta-item">{{ historyExecutionStatusLabel(entry) }}</span>
                      <span class="history-card__meta-item">{{ formatHistoryExecutionTime(entry.executionTimeMs, entry) }}</span>
                      <span class="history-card__meta-item">{{ formatChatHistoryTime(entry.createdAt) }}</span>
                    </div>
                    <div class="history-card__status">
                      <el-tag size="small" effect="light" class="history-tag history-tag--execution" :type="historyExecutionStatusType(entry)">
                        {{ historyExecutionStatusLabel(entry) }}
                      </el-tag>
                      <el-tag size="small" effect="light" class="history-tag history-tag--cache" :type="historyCacheTagType(entry)">
                        {{ historyCacheLabel(entry) }}
                      </el-tag>
                      <el-tag size="small" effect="light" class="history-tag history-tag--snapshot" :type="historySnapshotStatusType(entry)">
                        {{ historySnapshotStatusLabel(entry) }}
                      </el-tag>
                      <el-tag v-if="entry.isPinned" size="small" effect="light" class="history-tag history-tag--pinned" type="warning">
                        已钉入
                      </el-tag>
                    </div>
                  </button>
                </div>

                <div v-if="recentChatQueries?.length" class="history-detail" v-loading="historyDetailLoading">
                  <template v-if="selectedHistoryEntry">
                    <div class="history-detail__head">
                      <div class="history-detail__title-wrap">
                        <div class="history-detail__title">{{ selectedHistoryEntry.question || '未命名查询' }}</div>
                        <div class="history-detail__submeta">
                          <span>{{ selectedHistoryEntry.tableName || '未指定数据源' }}</span>
                          <span>{{ historyChartTypeLabel(selectedHistoryEntry.chartType) }}</span>
                          <span>{{ formatChatHistoryTime(selectedHistoryEntry.createdAt) }}</span>
                        </div>
                      </div>
                      <div class="history-detail__tags">
                        <el-tag size="small" effect="light" class="history-tag history-tag--execution" :type="historyExecutionStatusType(selectedHistoryEntry)">
                          {{ historyExecutionStatusLabel(selectedHistoryEntry) }}
                        </el-tag>
                        <el-tag size="small" effect="light" class="history-tag history-tag--risk" :type="riskTagType(selectedHistoryEntry.riskLevel)">
                          {{ selectedHistoryEntry.riskLevel || 'SAFE' }}
                        </el-tag>
                        <el-tag size="small" effect="light" class="history-tag history-tag--cache" :type="historyCacheTagType(selectedHistoryEntry)">
                          {{ historyCacheLabel(selectedHistoryEntry) }}
                        </el-tag>
                        <el-tag size="small" effect="light" class="history-tag history-tag--snapshot" :type="historySnapshotStatusType(selectedHistoryEntry)">
                          {{ historySnapshotStatusLabel(selectedHistoryEntry) }}
                        </el-tag>
                        <el-tag v-if="selectedHistoryEntry.isPinned" size="small" effect="light" class="history-tag history-tag--pinned" type="warning">
                          已钉入
                        </el-tag>
                      </div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">基本信息</div>
                      <div class="history-detail__status-grid">
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">执行状态</span>
                          <span class="history-detail__status-value">{{ historyExecutionStatusLabel(selectedHistoryEntry) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">快照状态</span>
                          <span class="history-detail__status-value">{{ historySnapshotStatusLabel(selectedHistoryEntry) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">执行耗时</span>
                          <span class="history-detail__status-value">{{ formatHistoryExecutionTime(selectedHistoryEntry.executionTimeMs, selectedHistoryEntry) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">{{ isHistoryAlertEntry(selectedHistoryEntry) ? '产物类型' : '图表行数' }}</span>
                          <span class="history-detail__status-value">{{ isHistoryAlertEntry(selectedHistoryEntry) ? '智能预警' : (selectedHistoryEntry.chartDataCount || 0) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">缓存命中</span>
                          <span class="history-detail__status-value">{{ historyCacheLabel(selectedHistoryEntry) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">是否归属会话</span>
                          <span class="history-detail__status-value">{{ selectedHistoryEntry.conversationId ? '是' : '否' }}</span>
                        </div>
                      </div>
                      <div v-if="selectedHistoryEntry.pinnedDashboardNames?.length" class="history-detail__hint">
                        已钉入：{{ selectedHistoryEntry.pinnedDashboardNames.join('、') }}
                      </div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">AI 推荐规则</div>
                      <div v-if="summarizeHistoryRule(selectedHistoryEntry).length" class="history-detail__kv-grid">
                        <div
                            v-for="item in summarizeHistoryRule(selectedHistoryEntry)"
                            :key="item.label"
                            class="history-detail__kv-item"
                        >
                          <span class="history-detail__kv-label">{{ item.label }}</span>
                          <span class="history-detail__kv-value">{{ item.value }}</span>
                        </div>
                      </div>
                      <div v-else class="history-detail__placeholder">暂无规则命中信息</div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">字段映射</div>
                      <div v-if="summarizeFieldMapping(selectedHistoryEntry.fieldMapping).length" class="history-detail__kv-grid">
                        <div
                            v-for="item in summarizeFieldMapping(selectedHistoryEntry.fieldMapping)"
                            :key="item.label"
                            class="history-detail__kv-item"
                        >
                          <span class="history-detail__kv-label">{{ item.label }}</span>
                          <span class="history-detail__kv-value">{{ item.value }}</span>
                        </div>
                      </div>
                      <div v-else class="history-detail__placeholder">暂无字段映射</div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">风险说明</div>
                      <div class="history-detail__text">
                        {{ selectedHistoryEntry.riskReason || '暂无风险说明' }}
                      </div>
                      <div v-if="historySensitiveFields(selectedHistoryEntry).length" class="history-detail__tag-list">
                        <el-tag
                            v-for="field in historySensitiveFields(selectedHistoryEntry)"
                            :key="`${selectedHistoryEntry.id}-sensitive-${field}`"
                            size="small"
                            type="warning"
                            effect="light"
                        >
                          {{ field }}
                        </el-tag>
                      </div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">图谱上下文</div>
                      <div v-if="summarizeGraphContext(selectedHistoryEntry).length" class="history-detail__context-list">
                        <span
                            v-for="(item, index) in summarizeGraphContext(selectedHistoryEntry)"
                            :key="`${selectedHistoryEntry.id}-ctx-${index}`"
                            class="history-detail__context-chip"
                        >
                          {{ item }}
                        </span>
                      </div>
                      <div v-else class="history-detail__placeholder">暂无上下文片段</div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">推理过程</div>
                      <template v-if="historyReplaySteps.length">
                        <div class="history-detail__reasoning-toolbar">
                          <div class="history-detail__reasoning-progress">
                            已展开 {{ historyReplayVisibleCount }}/{{ historyReplaySteps.length }} 步
                            <span v-if="historyReplayVisibleCount < historyReplaySteps.length">
                              ，剩余 {{ historyReplaySteps.length - historyReplayVisibleCount }} 步
                            </span>
                          </div>
                          <div class="history-detail__reasoning-actions">
                            <el-button size="small" plain @click="restartHistoryReplay">重播</el-button>
                            <el-button size="small" plain @click="toggleHistoryReplay">
                              {{ historyReplayPlaying ? '暂停' : '继续' }}
                            </el-button>
                            <el-button size="small" text @click="revealAllHistoryReplaySteps">全部展开</el-button>
                          </div>
                        </div>
                        <ol class="history-detail__reasoning-list">
                          <li
                              v-for="(step, index) in historyReplaySteps.slice(0, historyReplayVisibleCount)"
                              :key="`${selectedHistoryEntry.id}-reason-${index}`"
                              :class="[
                                'history-detail__reasoning-item',
                                { 'is-current': historyReplayPlaying && index === Math.max(historyReplayVisibleCount - 1, 0) }
                              ]"
                          >
                            <div class="history-detail__reasoning-marker">
                              <span class="history-detail__reasoning-dot"></span>
                              <span
                                  v-if="index < historyReplayVisibleCount - 1"
                                  class="history-detail__reasoning-line"
                              ></span>
                            </div>
                            <div class="history-detail__reasoning-card">
                              <div class="history-detail__reasoning-head">
                                <div class="history-detail__reasoning-stepno">STEP {{ index + 1 }}</div>
                                <div class="history-detail__reasoning-title">{{ step.title || `步骤 ${index + 1}` }}</div>
                              </div>
                              <div class="history-detail__reasoning-detail">
                                {{ step.detail || step.text || step.message || '暂无详情' }}
                              </div>
                            </div>
                          </li>
                        </ol>
                      </template>
                      <div v-else class="history-detail__placeholder">暂无可回放的推理步骤</div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">快照预览</div>
                      <div v-if="historySnapshotPreviewRows(selectedHistoryEntry).length" class="history-detail__snapshot-preview">
                        <div class="history-detail__snapshot-cards">
                          <article
                              v-for="card in historySnapshotPreviewCards(selectedHistoryEntry)"
                              :key="card.id"
                              class="history-detail__snapshot-card"
                          >
                            <div class="history-detail__snapshot-card-head">
                              <span class="history-detail__snapshot-card-label">{{ card.titleLabel }}</span>
                              <strong class="history-detail__snapshot-card-title">{{ card.titleValue }}</strong>
                            </div>
                            <div class="history-detail__snapshot-card-metric">
                              <span class="history-detail__snapshot-card-label">{{ card.metricLabel }}</span>
                              <span class="history-detail__snapshot-card-value">{{ card.metricValue }}</span>
                            </div>
                            <div v-if="card.extraFields.length" class="history-detail__snapshot-card-extra">
                              <div
                                  v-for="field in card.extraFields"
                                  :key="`${card.id}-${field.label}`"
                                  class="history-detail__snapshot-card-extra-item"
                              >
                                <span class="history-detail__snapshot-card-label">{{ field.label }}</span>
                                <span class="history-detail__snapshot-card-extra-value">{{ field.value }}</span>
                              </div>
                            </div>
                          </article>
                        </div>
                        <div class="history-detail__snapshot-table">
                          <table :style="{ minWidth: historySnapshotTableMinWidth(selectedHistoryEntry) }">
                            <thead>
                            <tr>
                              <th v-for="column in historySnapshotPreviewColumns(selectedHistoryEntry)" :key="column.key">
                                {{ column.label }}
                              </th>
                            </tr>
                            </thead>
                            <tbody>
                            <tr
                                v-for="(row, rowIndex) in historySnapshotPreviewRows(selectedHistoryEntry)"
                                :key="`${selectedHistoryEntry.id}-row-${rowIndex}`"
                            >
                              <td
                                  v-for="column in historySnapshotPreviewColumns(selectedHistoryEntry)"
                                  :key="`${selectedHistoryEntry.id}-${rowIndex}-${column.key}`"
                              >
                                {{ formatHistoryValue(row[column.key]) }}
                              </td>
                            </tr>
                            </tbody>
                          </table>
                        </div>
                        <div v-if="!isHistoryTableEntry(selectedHistoryEntry)" class="history-detail__thumbnail-card">
                          <div class="history-detail__thumbnail-head">
                            <div class="history-detail__thumbnail-title">图表缩略图</div>
                            <div class="history-detail__thumbnail-meta">
                              {{ selectedHistoryEntry.fieldMapping?.dimension || '维度' }} / {{ selectedHistoryEntry.fieldMapping?.metric || '指标' }}
                            </div>
                          </div>
                          <div ref="historyPreviewChartRef" class="history-detail__thumbnail-chart"></div>
                        </div>
                        <div v-else class="history-detail__hint">当前历史产物为表格结果，已使用上方表格快照展示，不生成图表缩略图。</div>
                      </div>
                      <div v-else class="history-detail__placeholder">
                        {{ isHistoryAlertEntry(selectedHistoryEntry) ? '预警历史记录不生成普通图表快照，可在上方查看规则详情。' : '暂无可预览的图表快照' }}
                      </div>
                    </div>

                    <div class="history-detail__section">
                      <div class="history-detail__section-title">SQL</div>
                      <div v-if="selectedHistoryEntry.sql" class="history-detail__sql-wrap">
                        <pre class="history-detail__sql">{{ selectedHistoryEntry.sql }}</pre>
                      </div>
                      <div v-else class="history-detail__placeholder">暂无 SQL</div>
                    </div>

                    <div
                        :class="[
                          'history-detail__footer',
                          { 'is-readonly': !isHistoryEntryRestorable(selectedHistoryEntry) }
                        ]"
                    >
                      <div
                          v-if="!isHistoryEntryRestorable(selectedHistoryEntry)"
                          class="history-detail__action-hint"
                      >
                        {{ historyRestoreHint(selectedHistoryEntry) }}
                      </div>
                      <el-button
                          size="small"
                          type="primary"
                          plain
                          :disabled="!isHistoryEntryRestorable(selectedHistoryEntry)"
                          @click="openHistoricalAnalysisFromHistory(selectedHistoryEntry)"
                      >
                        恢复图表
                      </el-button>
                      <el-button
                          size="small"
                          type="primary"
                          plain
                          :disabled="!selectedHistoryEntry?.question"
                          @click="reuseChatQuestion(selectedHistoryEntry)"
                      >
                        复用问题
                      </el-button>
                      <el-button
                          size="small"
                          plain
                          :disabled="!selectedHistoryEntry?.question"
                          @click="draftChatQuestionFromHistory(selectedHistoryEntry)"
                      >
                        带入提问
                      </el-button>
                      <el-button
                          size="small"
                          plain
                          :disabled="!isHistoryEntryRestorable(selectedHistoryEntry)"
                          @click="continueFromChatHistory(selectedHistoryEntry)"
                      >
                        继续追问
                      </el-button>
                      <el-button
                          size="small"
                          plain
                          :disabled="selectedHistoryEntry.isPinned || !isHistoryEntryRestorable(selectedHistoryEntry)"
                          @click="pinHistoryToDashboard(selectedHistoryEntry)"
                      >
                        {{ selectedHistoryEntry.isPinned ? '已钉入' : '钉入看板' }}
                      </el-button>
                      <el-button
                          v-if="selectedHistoryEntry.sql"
                          size="small"
                          plain
                          @click="copySqlToClipboard(selectedHistoryEntry.sql)"
                      >
                        复制SQL
                      </el-button>
                      <el-button size="small" type="danger" plain @click="removeRecentChatQuery(selectedHistoryEntry)">
                        删除记录
                      </el-button>
                    </div>
                  </template>
                </div>

                <div v-else class="history-empty-state">
                  <div class="history-empty-state__title">{{ historyEmptyTitle }}</div>
                  <div class="history-empty-state__text">{{ historyEmptyDescription }}</div>
                  <el-button
                      v-if="recentChatQueryError"
                      size="small"
                      type="primary"
                      @click="searchRecentChatQueries"
                  >
                    重新加载
                  </el-button>
                </div>
              </div>

              <div class="history-pagination">
                <el-pagination
                    layout="prev, pager, next, sizes"
                    :current-page="recentChatQueryPage"
                    :page-size="recentChatQueryPageSize"
                    :page-sizes="[8, 16, 24, 32]"
                    :total="recentChatQueryTotal"
                    :disabled="!recentChatQueries?.length && !recentChatQueryTotal"
                    @current-change="handleRecentChatPageChange"
                    @size-change="handleRecentChatPageSizeChange"
                />
              </div>
            </div>
          </el-drawer>
          <el-drawer
              v-model="advancedHistoryVisible"
              title="预测与情景模拟记录"
              size="520px"
              destroy-on-close
          >
            <div class="advanced-history">
              <section class="advanced-alert-rule-manager">
                <div class="advanced-alert-rule-manager__head">
                  <div>
                    <h4>预警规则</h4>
                    <p>管理已保存的离线预警规则</p>
                  </div>
                  <el-button size="small" plain :loading="advancedAlertRulesLoading" @click="loadAdvancedAlertRules">刷新</el-button>
                </div>
                <div v-if="advancedAlertRules.length" class="advanced-alert-rule-list">
                  <article
                      v-for="rule in advancedAlertRules"
                      :key="rule.id"
                      class="advanced-alert-rule-item"
                  >
                    <div class="advanced-alert-rule-item__main">
                      <strong>{{ formatAlertRuleTitle(rule) }}</strong>
                      <span>{{ formatAlertRuleMeta(rule) }}</span>
                    </div>
                    <el-tag size="small" effect="light" :type="rule.status === 'ACTIVE' ? 'success' : 'info'">
                      {{ rule.status === 'ACTIVE' ? '已启用' : rule.status === 'DISABLED' ? '已停用' : rule.status }}
                    </el-tag>
                    <div class="advanced-alert-rule-item__actions">
                  <el-button size="small" text type="primary" @click="editAdvancedAlertRule(rule)">编辑</el-button>
                      <el-button size="small" text type="success" @click="runAdvancedAlertRuleDetection(rule)">检测</el-button>
                      <el-button size="small" text @click="toggleAdvancedAlertRule(rule)">
                        {{ rule.status === 'ACTIVE' ? '停用' : '启用' }}
                      </el-button>
                      <el-button size="small" text type="danger" @click="removeAdvancedAlertRule(rule)">删除</el-button>
                    </div>
                  </article>
                </div>
                <div v-else class="advanced-alert-rule-empty">
                  暂无已保存的预警规则。
                </div>
              </section>
              <section class="advanced-alert-event-manager">
                <div class="advanced-alert-rule-manager__head">
                  <div>
                    <h4>预警事件</h4>
                    <p>展示离线检测生成的异常事件</p>
                  </div>
                  <el-button size="small" plain :loading="advancedAlertEventsLoading" @click="loadAdvancedAlertEvents">刷新</el-button>
                </div>
                <div v-if="advancedAlertEvents.length" class="advanced-alert-rule-list">
                  <article
                      v-for="event in advancedAlertEvents"
                      :key="event.id"
                      class="advanced-alert-event-item"
                  >
                    <div class="advanced-alert-rule-item__main">
                      <strong>{{ formatAlertEventTitle(event) }}</strong>
                      <span>{{ event.reason }}</span>
                    </div>
                    <el-tag size="small" effect="light" type="warning">{{ event.status || 'OPEN' }}</el-tag>
                  </article>
                </div>
                <div v-else class="advanced-alert-rule-empty">
                  暂无预警事件。
                </div>
              </section>
              <div v-if="advancedAnalysisHistory.length" class="advanced-history__list">
                <article
                    v-for="item in advancedAnalysisHistory"
                    :key="item.id"
                    class="advanced-history__item"
                >
                  <div class="advanced-history__head">
                    <div>
                      <div class="advanced-history__type">{{ advancedAnalysisTypeLabel(item.type) }}</div>
                      <h4>{{ item.title }}</h4>
                    </div>
                    <el-tag size="small" effect="light">{{ item.createdAt }}</el-tag>
                  </div>
                  <p>{{ item.summary }}</p>
                  <div class="advanced-history__meta">
                    <span>{{ item.tableName || '当前对话上下文' }}</span>
                    <span>{{ item.metric || '自动推断指标' }}</span>
                  </div>
                  <div class="advanced-history__actions">
                    <el-button size="small" type="primary" plain @click="restoreAdvancedAnalysis(item)">回到对话</el-button>
                    <el-button size="small" plain @click="removeAdvancedAnalysisHistory(item)">删除</el-button>
                  </div>
                </article>
              </div>
              <div v-else class="advanced-history__empty">
                暂无已保存的预测、推演或预警记录。
              </div>
            </div>
          </el-drawer>
          <el-dialog
              v-model="alertRuleEditorVisible"
              title="编辑预警规则"
              width="640px"
              destroy-on-close
          >
            <el-form label-position="top" class="forecast-confirm-form">
              <div class="forecast-confirm-grid">
                <el-form-item label="时间字段">
                  <el-select v-model="alertRuleEditorForm.timeField" class="full-width" filterable>
                    <el-option
                        v-for="field in alertRuleEditorMeta.timeFields"
                        :key="field.columnName"
                        :label="formatAdvancedFieldLabel(field)"
                        :value="field.columnName"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="指标字段">
                  <el-select v-model="alertRuleEditorForm.metricField" class="full-width" filterable>
                    <el-option
                        v-for="field in alertRuleEditorMeta.numericFields"
                        :key="field.columnName"
                        :label="formatAdvancedFieldLabel(field)"
                        :value="field.columnName"
                    />
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="过滤条件（可选）">
                <el-input v-model.trim="alertRuleEditorForm.filterExpression" placeholder="例如：region = '华东'" />
              </el-form-item>
              <div class="forecast-confirm-grid">
                <el-form-item label="聚合粒度">
                  <el-select v-model="alertRuleEditorForm.granularity">
                    <el-option label="按日" value="day" />
                    <el-option label="按周" value="week" />
                    <el-option label="按月" value="month" />
                    <el-option label="按季度" value="quarter" />
                    <el-option label="按年" value="year" />
                  </el-select>
                </el-form-item>
                <el-form-item label="检测周期">
                  <el-select v-model="alertRuleEditorForm.detectionCycle">
                    <el-option label="每小时" value="hourly" />
                    <el-option label="每日" value="daily" />
                    <el-option label="每周" value="weekly" />
                    <el-option label="每月" value="monthly" />
                  </el-select>
                </el-form-item>
              </div>
              <div class="forecast-confirm-grid">
                <el-form-item label="判断条件">
                  <el-select v-model="alertRuleEditorForm.operator">
                    <el-option label="低于阈值" value="lt" />
                    <el-option label="高于阈值" value="gt" />
                    <el-option label="Z-Score 异常波动" value="zscore" />
                  </el-select>
                </el-form-item>
                <el-form-item label="阈值">
                  <el-input-number
                      v-model="alertRuleEditorForm.threshold"
                      :min="0"
                      :disabled="alertRuleEditorForm.operator === 'zscore'"
                  />
                </el-form-item>
              </div>
              <el-form-item label="通知渠道">
                <el-checkbox-group v-model="alertRuleEditorForm.channels">
                  <el-checkbox label="email">邮件</el-checkbox>
                  <el-checkbox label="dingtalk">钉钉</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="alertRuleEditorVisible = false">取消</el-button>
              <el-button type="primary" :loading="alertRuleEditorSaving" @click="submitAlertRuleEditor">保存修改</el-button>
            </template>
          </el-dialog>
          <el-dialog
              v-model="advancedAnalysisDialogVisible"
              :title="activeAdvancedAnalysis ? advancedAnalysisTypeLabel(activeAdvancedAnalysis.type) : '高级分析'"
              width="860px"
              destroy-on-close
              class="advanced-analysis-dialog"
          >
            <AdvancedAnalysisCard
              v-if="activeAdvancedAnalysis"
              :analysis="activeAdvancedAnalysis"
              :explain-loading="advancedAnalysisExplainingId === activeAdvancedAnalysis.id"
              @recalculate="recalculateAdvancedAnalysis"
              @save="saveAdvancedAnalysis"
              @pin="pinAdvancedAnalysis"
              @manage-alerts="openAdvancedAnalysisManagePage"
              @explain="explainAdvancedAnalysis"
            />
          </el-dialog>
          <el-dialog
              v-model="forecastConfirmVisible"
              title="确认预测参数"
              width="560px"
              destroy-on-close
          >
            <el-form label-position="top" class="forecast-confirm-form">
              <el-alert
                  title="系统已根据数据源字段和自然语言推断预测参数，请确认后执行真实预测。"
                  type="info"
                  :closable="false"
                  show-icon
              />
              <el-alert
                  v-if="!forecastConfirmMeta.timeFields.length"
                  title="当前数据源没有可识别的日期/时间字段，无法基于真实时间序列预测。请切换包含日期字段的数据源，或先做按月/按日统计后再预测。"
                  type="warning"
                  :closable="false"
                  show-icon
              />
              <el-form-item label="时间字段">
                <el-select v-model="forecastConfirmForm.timeField" class="full-width" filterable placeholder="请选择时间字段">
                  <el-option
                      v-for="field in forecastConfirmMeta.timeFields"
                      :key="field.columnName"
                      :label="formatAdvancedFieldLabel(field)"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="指标字段">
                <el-select v-model="forecastConfirmForm.metricField" class="full-width" filterable placeholder="请选择指标字段">
                  <el-option
                      v-for="field in forecastConfirmMeta.numericFields"
                      :key="field.columnName"
                      :label="formatAdvancedFieldLabel(field)"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="过滤条件（可选）">
                <el-input
                    v-model.trim="forecastConfirmForm.filterExpression"
                    placeholder="支持显示名/源字段名，例如：region = '华东' AND channel = '直营网'"
                />
              </el-form-item>
              <div class="forecast-confirm-grid">
                <el-form-item label="聚合粒度">
                  <el-select v-model="forecastConfirmForm.granularity">
                    <el-option label="按日" value="day" />
                    <el-option label="按周" value="week" />
                    <el-option label="按月" value="month" />
                    <el-option label="按季度" value="quarter" />
                    <el-option label="按年" value="year" />
                  </el-select>
                </el-form-item>
                <el-form-item label="预测点数">
                  <el-input-number v-model="forecastConfirmForm.horizon" :min="1" :max="60" />
                </el-form-item>
              </div>
              <el-form-item label="算法">
                <el-select v-model="forecastConfirmForm.algorithm" class="full-width">
                  <el-option label="Prophet-like" value="Prophet" />
                  <el-option label="Holt-Winters" value="Holt-Winters" />
                </el-select>
              </el-form-item>
              <div v-if="forecastConfirmForm.algorithm === 'Holt-Winters'" class="forecast-confirm-grid">
                <el-form-item label="Alpha">
                  <el-input-number v-model="forecastConfirmForm.alpha" :min="0.01" :max="0.99" :step="0.01" />
                </el-form-item>
                <el-form-item label="Beta">
                  <el-input-number v-model="forecastConfirmForm.beta" :min="0.01" :max="0.99" :step="0.01" />
                </el-form-item>
                <el-form-item label="Gamma">
                  <el-input-number v-model="forecastConfirmForm.gamma" :min="0.01" :max="0.99" :step="0.01" />
                </el-form-item>
                <el-form-item label="季节周期">
                  <el-input-number v-model="forecastConfirmForm.seasonLength" :min="0" :max="60" />
                </el-form-item>
              </div>
            </el-form>
            <template #footer>
              <el-button @click="cancelForecastConfirm">取消</el-button>
              <el-button type="primary" :disabled="!forecastConfirmMeta.timeFields.length" @click="submitForecastConfirm">执行真实预测</el-button>
            </template>
          </el-dialog>
          <el-dialog
              v-model="whatIfConfirmVisible"
              title="确认推演参数"
              width="860px"
              destroy-on-close
              class="whatif-confirm-dialog"
          >
            <el-form label-position="top" class="forecast-confirm-form">
              <el-alert
                  title="系统已根据数据源字段和自然语言推断推演参数，请确认后执行真实推演。"
                  type="info"
                  :closable="false"
                  show-icon
              />
              <el-form-item label="目标指标">
                <el-select v-model="whatIfConfirmForm.targetMetric" class="full-width" filterable placeholder="请选择目标指标">
                  <el-option
                      v-for="field in whatIfConfirmMeta.numericFields"
                      :key="field.columnName"
                      :label="formatAdvancedFieldLabel(field)"
                      :value="field.columnName"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="业务公式（可选）">
                <el-input
                    v-model.trim="whatIfConfirmForm.formula"
                    placeholder="例如：SAFE_DIVIDE(profit, sales_amt, 0)，支持 IF/ABS/MIN/MAX/ROUND"
                    clearable
                />
                <div class="forecast-confirm-hint">填写后将按业务公式计算基准与情景结果；支持字段显示名/源字段名/列名、IF 条件、ABS/MIN/MAX/ROUND/SAFE_DIVIDE。</div>
              </el-form-item>
              <el-form-item v-if="whatIfConfirmForm.formula" label="公式计算口径">
                <el-select v-model="whatIfConfirmForm.formulaScope" class="full-width">
                  <el-option label="聚合口径（字段均值计算）" value="aggregate" />
                  <el-option label="按行口径（逐行计算后求平均）" value="row" />
                </el-select>
                <div class="forecast-confirm-hint">聚合口径兼容原有方案；按行口径适合利润率、转化率等需要逐行先算再汇总的公式。</div>
              </el-form-item>
              <div class="whatif-variable-toolbar">
                <div class="whatif-variable-toolbar__title">变量列表</div>
                <el-button size="small" plain @click="addWhatIfConfirmVariable">新增变量</el-button>
              </div>
              <div class="whatif-variable-list">
                <div
                    v-for="(variable, index) in whatIfConfirmForm.variables"
                    :key="`${variable.name || 'variable'}-${index}`"
                    class="whatif-variable-item"
                >
                  <div class="whatif-variable-item__main">
                    <el-form-item label="变量字段">
                      <el-select
                          v-model="variable.field"
                          class="full-width"
                          filterable
                          placeholder="变量字段"
                      >
                        <el-option
                            v-for="field in whatIfConfirmMeta.numericFields"
                            :key="field.columnName"
                            :label="formatAdvancedFieldLabel(field)"
                            :value="field.columnName"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="变量名称">
                      <el-input v-model="variable.name" class="full-width" placeholder="例如：销售额" />
                    </el-form-item>
                    <el-form-item label="调整方式">
                      <el-select v-model="variable.mode" class="full-width" placeholder="方式">
                        <el-option label="百分比" value="percent" />
                        <el-option label="绝对值" value="absolute" />
                        <el-option label="设为固定值" value="set" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="调整值">
                      <el-input-number
                          v-model="variable.change"
                          class="full-width"
                          :controls="false"
                          :min="variable.mode === 'percent' ? -100 : undefined"
                          :max="variable.mode === 'percent' ? 100 : undefined"
                          :step="1"
                      />
                    </el-form-item>
                  </div>
                  <div class="whatif-variable-item__limits">
                    <el-form-item label="最小值（可选）">
                      <el-input-number v-model="variable.min" class="full-width" placeholder="最小值" :controls="false" />
                    </el-form-item>
                    <el-form-item label="最大值（可选）">
                      <el-input-number v-model="variable.max" class="full-width" placeholder="最大值" :controls="false" />
                    </el-form-item>
                    <el-button class="whatif-variable-item__delete" plain type="danger" @click="removeWhatIfConfirmVariable(index)">删除变量</el-button>
                  </div>
                </div>
              </div>
            </el-form>
            <template #footer>
              <el-button @click="cancelWhatIfConfirm">取消</el-button>
              <el-button type="primary" @click="submitWhatIfConfirm">执行真实推演</el-button>
            </template>
          </el-dialog>
          <el-dialog
              v-model="alertConfirmVisible"
              title="确认预警规则"
              width="640px"
              destroy-on-close
          >
            <el-form label-position="top" class="forecast-confirm-form">
              <el-alert
                  title="系统已根据自然语言生成预警规则草案，请确认字段、阈值和通知渠道后保存。"
                  type="info"
                  :closable="false"
                  show-icon
              />
              <div class="forecast-confirm-grid">
                <el-form-item label="时间字段">
                  <el-select v-model="alertConfirmForm.timeField" class="full-width" filterable placeholder="请选择时间字段">
                    <el-option
                        v-for="field in alertConfirmMeta.timeFields"
                        :key="field.columnName"
                        :label="formatAdvancedFieldLabel(field)"
                        :value="field.columnName"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="指标字段">
                  <el-select v-model="alertConfirmForm.metricField" class="full-width" filterable placeholder="请选择指标字段">
                    <el-option
                        v-for="field in alertConfirmMeta.numericFields"
                        :key="field.columnName"
                        :label="formatAdvancedFieldLabel(field)"
                        :value="field.columnName"
                    />
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="过滤条件（可选）">
                <el-input
                    v-model.trim="alertConfirmForm.filterExpression"
                    placeholder="例如：region = '华东' AND channel = '直营网'"
                />
              </el-form-item>
              <div class="forecast-confirm-grid">
                <el-form-item label="聚合粒度">
                  <el-select v-model="alertConfirmForm.granularity">
                    <el-option label="按日" value="day" />
                    <el-option label="按周" value="week" />
                    <el-option label="按月" value="month" />
                    <el-option label="按季度" value="quarter" />
                    <el-option label="按年" value="year" />
                  </el-select>
                </el-form-item>
                <el-form-item label="检测周期">
                  <el-select v-model="alertConfirmForm.detectionCycle">
                    <el-option label="每小时" value="hourly" />
                    <el-option label="每日" value="daily" />
                    <el-option label="每周" value="weekly" />
                    <el-option label="每月" value="monthly" />
                  </el-select>
                </el-form-item>
              </div>
              <div class="forecast-confirm-grid">
                <el-form-item label="判断条件">
                  <el-select v-model="alertConfirmForm.operator">
                    <el-option label="低于阈值" value="lt" />
                    <el-option label="高于阈值" value="gt" />
                    <el-option label="Z-Score 异常波动" value="zscore" />
                  </el-select>
                </el-form-item>
                <el-form-item label="阈值">
                  <el-input-number
                      v-model="alertConfirmForm.threshold"
                      :min="0"
                      :disabled="alertConfirmForm.operator === 'zscore'"
                  />
                </el-form-item>
              </div>
              <el-form-item label="通知渠道">
                <el-checkbox-group v-model="alertConfirmForm.channels">
                  <el-checkbox label="email">邮件</el-checkbox>
                  <el-checkbox label="dingtalk">钉钉</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="cancelAlertConfirm">取消</el-button>
              <el-button type="primary" :disabled="!alertConfirmMeta.timeFields.length" @click="submitAlertConfirm">保存预警规则</el-button>
            </template>
          </el-dialog>
</section>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { ArrowDown, ArrowLeftBold, ArrowRightBold, Box, Calendar, Close, DataAnalysis, DataBoard, Document, Edit, Files, Management, Microphone, Promotion, QuestionFilled, Refresh, Search, Setting, Share, Sort, TrendCharts, View } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import BusinessDictionaryView from '../../components/BusinessDictionaryView.vue'
import AdvancedAnalysisCard from '../../components/AdvancedAnalysisCard.vue'
import CollabChatInsightPanel from '../../components/collab/CollabChatInsightPanel.vue'
import chatQueryAvatar from '../../assets/chat-query-avatar.png'
import chatPeopleAvatar from '../../assets/chat-people.png'
import noImageIllustration from '../../assets/no_image.png'
import {
  explainAdvancedAnalysisResult,
  fetchAdvancedAnalysisFieldMeta,
  deleteAdvancedAlertRule,
  getAdvancedAlertRule,
  listAdvancedAlertEvents,
  listAdvancedAlertRules,
  saveAdvancedAnalysisChatRecord,
  saveAdvancedAnalysisPlan,
  saveAdvancedAlertRule,
  parseAdvancedAnalysisIntent,
  streamAdvancedAnalysisIntent,
  runAdvancedForecast,
  runAdvancedForecastFromSeries,
  runAdvancedAlertDetection,
  runAdvancedWhatIf,
  updateAdvancedAlertRule,
  updateAdvancedAlertRuleStatus
} from '../../api/advancedAnalysis'
import { isAlertOperationQuestion } from '../../utils/alertOperationQuestion'
import {
  chartRecommendationScenarioLabel,
  formatChartRecommendationExplain,
  formatChartRecommendationStatus
} from '../../utils/chartRecommendationText'

const localVoiceGenderOptions = [
  { label: '男声', value: 'male' },
  { label: '女声', value: 'female' }
]

const ADVANCED_THINKING_LOG_LIMIT = 100

const {
  API_BASE,
  accessibleTables,
  activeModule,
  advancedAlertContext,
  adminPermissionRequests,
  adminRequestStatus,
  auditExecuteStatus,
  auditLogs,
  auditRiskLevel,
  body,
  chartTypeLabel,
  chartTypeSwitchOptions,
  changeLastAnalysisChartType,
  isAiRecommendedChartType,
  chartSortMode,
  chartAnimationMeta,
  chatDom,
  createDatasource,
  currentChartType,
  currentDiagnosis,
  isLastAnalysisTable,
  isLastAnalysisMetric,
  lastAnalysisTableColumns,
  lastAnalysisTableRows,
  lastAnalysisMetricDisplay,
  data,
  datasourceForm,
  dateFields,
  detail,
  diagnosisForm,
  diagnosisLoading,
  diagnosisReports,
  dimensionCandidateFields,
  canDiagnoseLastAnalysis,
  canRegenerateLastAnalysis,
  canPinLastAnalysis,
  diagnoseFromLastAnalysis,
  field,
  fieldLabel,
  fields,
  exportChartAsImage,
  fillCurrentDatasource,
  formData,
  isAdminModule,
  isAdminUser,
  isPermissionModule,
  isStreaming,
  businessDictionaryPanelVisible,
  businessDictionaryFocusModelId,
  lastAnalysis,
  loadAdminPermissionRequests,
  loadAuditLogs,
  loadDatasources,
  loadDiagnosisReportDetail,
  loadDiagnosisReports,
  loadBusinessModels,
  loadFields,
  loadPermissionCenter,
  loadPreview,
  loadSchemaTables,
  loadTables,
  loading,
  streamAbortController,
  activeChatRequestId,
  stopRequested,
  chatModelOptions,
  selectedChatModelId,
  selectedChatModel,
  messages,
  moduleSubtitle,
  moduleTitle,
  myPermissionRequests,
  nextStatus,
  numericFields,
  uploadTables,
  officialQueryTables,
  officialDatasources,
  onFileChange,
  onFileRemove,
  parsed,
  permissionForm,
  permissionOverview,
  permissionStatusText,
  permissionStatusType,
  placeholderStep,
  previewColumns,
  previewRows,
  question,
  copySqlToClipboard,
  chatSessions,
  activeChatSessionId,
  chatSessionLoading,
  chatSessionKeyword,
  chatSessionStatus,
  recentChatQueries,
  recentChatQueryKeyword,
  recentChatQueryTableName,
  recentChatQueryChartType,
  recentChatQueryRiskLevel,
  recentChatQueryExecutionStatus,
  recentChatQueryDateRange,
  recentChatQuerySortDirection,
  recentChatQueryPage,
  recentChatQueryPageSize,
  recentChatQueryTotal,
  recentChatQueryLoading,
  recentChatQueryError,
  recentChatQueryErrorType,
  searchChatSessions,
  resetChatSessionSearch,
  refreshActiveChatSessionSummary,
  renameChatSession,
  updateChatSessionStatus,
  deleteChatSession,
  createChatSession,
  selectChatSession,
  setActiveBranchParent,
  clearActiveBranchParent,
  activeBranchParentTurnMeta,
  formatChatHistoryTime,
  renderChart,
  requestableTables,
  result,
  reviewPermission,
  riskTagType,
  runDiagnosis,
  schemaFields,
  schemaTables,
  selectDatasource,
  selectSchemaTable,
  selectTable,
  selectedDatasourceId,
  selectedTableName,
  activeBusinessModelId,
  selectedChatBusinessModelId,
  lastCreatedBusinessModelId,
  lastAppliedBusinessModelId,
  chatBusinessModelOptions,
  businessModels,
  sendQuestion,
  regenerateLastAnalysis,
  openPinDialog,
  openPinDialogForAnalysis,
  pinChartToDashboard,
  openHistoricalAnalysis,
  openHistoricalAnalysisFromHistory,
  reuseChatQuestion,
  continueFromChatHistory,
  draftChatQuestionFromHistory,
  pinDialogVisible,
  pinning,
  pinDashboardId,
  dashboardOptions,
  voicePanelVisible,
  voiceLocaleOptions,
  recognitionLocale,
  voiceLocale,
  selectedVoiceGender,
  speechRate,
  speechVolume,
  autoSpeakConclusion,
  autoSendAfterRecognize,
  recognitionSupported,
  speechSupported,
  voiceCapabilityText,
  voiceStatusText,
  listening,
  speaking,
  speechPaused,
  recognitionError,
  interimTranscript,
  finalTranscript,
  voiceHistory,
  startVoiceQuestionInput,
  stopVoiceQuestionInput,
  speakChatBubble,
  stopVoicePlayback,
  toggleVoicePlayback,
  clearVoiceHistory,
  normalizeVoiceQuestion,
  stopQuestionGeneration,
  removeRecentChatQuery,
  searchRecentChatQueries,
  resetRecentChatQuerySearch,
  handleRecentChatPageChange,
  handleRecentChatPageSizeChange,
  loadPinnedHistoryIds,
  ensureHistoryEntrySnapshot,
  seriesData,
  statusTagType,
  submitPermissionRequest,
  submitUpload,
  syncDatasourceSchema,
  tables,
  testDatasource,
  toggleDatasource,
  unwrap,
  updateSchemaField,
  handleChatBusinessModelChange,
  handleChatModelChange,
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  xAxisData
} = inject('workbench')

const metricTrendText = (trend) => {
  const text = String(trend || '').toLowerCase()
  if (text === 'up') return '上升'
  if (text === 'down') return '下降'
  if (text === 'flat') return '持平'
  return ''
}

const lastAnalysisChartLinkId = computed(() => {
  const analysis = lastAnalysis.value
  if (!analysis) return null
  return analysis.chartId || analysis.artifactId || analysis.layoutItemId || null
})

const chatModelPickerVisible = ref(false)

const formatPinDashboardLabel = (dashboard) => {
  const name = String(dashboard?.name || `看板#${dashboard?.id || ''}`).trim()
  const tag = String(dashboard?.pinTargetLabel || '').trim()
  if (tag) return `${name}（${tag}）`
  return dashboard?.isPublic ? `${name}（公开）` : name
}

const formatTableOptionLabel = (table) => {
  const displayName = String(table?.displayName || '').trim()
  const tableName = String(table?.tableName || '').trim()
  if (displayName && tableName && displayName !== tableName) {
    return `${displayName}（${tableName}）`
  }
  return displayName || tableName || '未命名数据源'
}

const formatBusinessModelLabel = (model) => {
  const modelName = String(model?.modelName || '').trim()
  const tableName = String(model?.tableName || '').trim()
  if (modelName && tableName) {
    return `${modelName}（${tableName}）`
  }
  return modelName || `模型 ${model?.id ?? ''}`
}

const formatChatModelLabel = (model) => {
  const name = String(model?.name || model?.model || model?.id || '').trim()
  return name || '默认模型'
}

const selectedChatModelPayload = computed(() => {
  const model = selectedChatModel?.value || selectedChatModel || {}
  return {
    modelId: model?.id || '',
    modelName: model?.name || model?.model || '',
    modelCategory: model?.category || ''
  }
})

const currentChatModelShortLabel = computed(() => {
  const raw = formatChatModelLabel(selectedChatModel?.value || selectedChatModel || {})
  const compact = raw
    .replace(/^deepseek-/i, 'DS-')
    .replace(/^qwen-/i, 'Qwen-')
    .replace(/^gpt-/i, 'GPT-')
  return compact.length > 10 ? `${compact.slice(0, 9)}...` : compact
})

const selectChatModel = (modelId) => {
  handleChatModelChange(modelId)
  chatModelPickerVisible.value = false
}

const selectedTableSummary = computed(() => {
  const matched = [...(uploadTables?.value || []), ...(officialQueryTables?.value || [])]
    .find(item => String(item?.tableName || '') === String(selectedTableName?.value || ''))
  return matched ? formatTableOptionLabel(matched) : ''
})

const selectedBusinessModelSummary = computed(() => {
  const matched = (businessModels?.value || [])
    .find(item => String(item?.id) === String(selectedChatBusinessModelId?.value ?? ''))
  return matched ? formatBusinessModelLabel(matched) : ''
})

const businessModelEmptyHint = computed(() => {
  if (!selectedTableName?.value) {
    return '请先选择数据源'
  }
  if (!chatBusinessModelOptions?.value?.length) {
    return '当前数据源下暂无业务模型'
  }
  return '请选择要修改的业务模型'
})

const semanticEvidenceItems = computed(() =>
  Array.isArray(lastAnalysis?.value?.semanticEvidence) ? lastAnalysis.value.semanticEvidence : []
)

const semanticKnowledgeGraphRef = ref(null)
let semanticKnowledgeGraphInstance = null

const semanticGraphText = (...values) => values
  .map(value => String(value ?? '').trim())
  .find(Boolean) || ''

const semanticGraphNodeCategory = (type = '') => {
  const value = String(type || '').replace(/[-_\s]/g, '').toLowerCase()
  if (value.includes('table') || value.includes('datasource') || value.includes('source')) return 0
  if (value.includes('field') || value.includes('column')) return 1
  if (value.includes('metric') || value.includes('indicator') || value.includes('formula')) return 2
  if (value.includes('dimension') || value.includes('dict') || value.includes('business') || value.includes('semantic')) return 3
  return 4
}

const semanticGraphNodeTypeLabel = (type = '') => {
  const value = String(type || '').replace(/[-_\s]/g, '').toLowerCase()
  if (value.includes('table') || value.includes('datasource') || value.includes('source')) return '数据表'
  if (value.includes('field') || value.includes('column')) return '字段'
  if (value.includes('metric') || value.includes('indicator') || value.includes('formula')) return '业务指标'
  if (value.includes('dimension')) return '业务维度'
  if (value.includes('dict')) return '业务字典'
  if (value.includes('business') || value.includes('semantic')) return '语义依据'
  return type || '节点'
}

const semanticGraphNodeKey = (node = {}, index = 0, prefix = 'node') =>
  semanticGraphText(node.nodeKey, node.id, node.key, node.sourceId, node.field, node.label, node.name, `${prefix}-${index}`)

const semanticGraphLabel = (node = {}, fallback = '') =>
  semanticGraphText(node.label, node.name, node.displayName, node.fieldDisplayName, node.field, node.nodeKey, node.sourceId, fallback)

const semanticGraphRelationLabel = (value = '') => {
  const raw = String(value || '').trim()
  const normalized = raw.replace(/[-\s]/g, '_').toUpperCase()
  const relationMap = {
    HAS_FIELD: '包含字段',
    CONTAINS_FIELD: '包含字段',
    FIELD_OF: '所属字段',
    USES_FIELD: '使用字段',
    USE_FIELD: '使用字段',
    DEFINES_METRIC: '定义指标',
    DEFINE_METRIC: '定义指标',
    METRIC_OF: '所属指标',
    HAS_METRIC: '包含指标',
    HAS_DIMENSION: '包含维度',
    USES_DIMENSION: '使用维度',
    MAPS_TO: '映射到',
    MAPPED_TO: '映射到',
    ALIAS_OF: '别名',
    SAME_AS: '等同于',
    RELATED_TO: '关联',
    RELATED: '关联',
    DERIVED_FROM: '派生自',
    CALCULATED_BY: '计算公式',
    DEPENDS_ON: '依赖',
    BELONGS_TO: '属于',
    PART_OF: '属于',
    SOURCE_OF: '来源',
    REFERENCES: '引用',
    REFERS_TO: '引用',
    FILTERS_BY: '筛选条件',
    GROUPS_BY: '分组维度',
    ORDERS_BY: '排序依据',
    JOINS_WITH: '关联表'
  }
  if (relationMap[normalized]) return relationMap[normalized]
  if (/^[A-Z0-9_]+$/.test(normalized)) {
    return normalized
      .toLowerCase()
      .split('_')
      .filter(Boolean)
      .map(part => relationMap[part.toUpperCase()] || part)
      .join(' ')
      .replace(/^has field$/, '包含字段')
      .replace(/^uses field$/, '使用字段')
      .replace(/^defines metric$/, '定义指标')
      .replace(/^related to$/, '关联')
  }
  return raw || '关联'
}

const buildSemanticKnowledgeGraph = (analysis = {}) => {
  const nodes = []
  const edges = []
  const nodeMap = new Map()
  const edgeKeys = new Set()
  const graphPath = analysis?.graphPath && typeof analysis.graphPath === 'object' ? analysis.graphPath : {}
  const rawGraphNodes = Array.isArray(graphPath.nodes)
    ? graphPath.nodes
    : (Array.isArray(graphPath.ragContext) ? graphPath.ragContext : [])
  const rawGraphEdges = Array.isArray(graphPath.edges) ? graphPath.edges : []
  const fallbackContext = Array.isArray(analysis?.graphContext) ? analysis.graphContext : []
  const evidence = Array.isArray(analysis?.semanticEvidence) ? analysis.semanticEvidence : []
  const mapping = analysis?.fieldMapping && typeof analysis.fieldMapping === 'object' ? analysis.fieldMapping : {}
  const tableName = semanticGraphText(analysis?.tableName, analysis?.sourceTableName, selectedTableName?.value)
  const tableKey = tableName ? `table:${tableName}` : ''

  const addNode = (key, name, type = 'node', extra = {}) => {
    const safeKey = semanticGraphText(key, name)
    if (!safeKey || nodeMap.has(safeKey) || nodes.length >= 18) return safeKey
    const label = semanticGraphText(name, safeKey)
    const node = {
      id: safeKey,
      name: label.length > 18 ? `${label.slice(0, 18)}...` : label,
      value: label,
      category: semanticGraphNodeCategory(type),
      symbolSize: semanticGraphNodeCategory(type) === 0 ? 58 : semanticGraphNodeCategory(type) === 2 ? 52 : 44,
      nodeType: semanticGraphNodeTypeLabel(type),
      rawType: type,
      ...extra
    }
    nodes.push(node)
    nodeMap.set(safeKey, node)
    return safeKey
  }

  const addEdge = (source, target, label = '关联') => {
    if (!source || !target || source === target || !nodeMap.has(source) || !nodeMap.has(target)) return
    const displayLabel = semanticGraphRelationLabel(label)
    const key = `${source}->${target}:${displayLabel}`
    if (edgeKeys.has(key) || edges.length >= 28) return
    edgeKeys.add(key)
    edges.push({ source, target, value: displayLabel, label: displayLabel, rawLabel: label })
  }

  if (tableKey) {
    addNode(tableKey, tableName, 'table', { fixed: false })
  }

  const graphNodes = rawGraphNodes.length ? rawGraphNodes : fallbackContext
  graphNodes.slice(0, 12).forEach((node, index) => {
    if (!node || typeof node !== 'object') return
    const key = semanticGraphNodeKey(node, index, 'graph')
    const label = semanticGraphLabel(node, key)
    const type = semanticGraphText(node.nodeType, node.type, node.sourceType)
    addNode(key, label, type, {
      content: semanticGraphText(node.content, node.description, node.summary),
      weight: Number(node.weight || 1)
    })
  })

  rawGraphEdges.slice(0, 24).forEach(edge => {
    const source = semanticGraphText(edge?.fromKey, edge?.source, edge?.sourceKey, edge?.sourceNodeKey, edge?.from)
    const target = semanticGraphText(edge?.toKey, edge?.target, edge?.targetKey, edge?.targetNodeKey, edge?.to)
    addEdge(source, target, semanticGraphText(edge?.relationType, edge?.type, edge?.label, '关联'))
  })

  const fieldNodes = [
    { key: mapping.dimensionKey || mapping.dimensionField || mapping.xField, label: mapping.dimension, type: 'dimension', relation: '维度字段' },
    { key: mapping.metricKey || mapping.metricField || mapping.yField, label: mapping.metric, type: 'metric', relation: '指标字段' },
    { key: mapping.timeField, label: mapping.timeFieldLabel || mapping.timeField, type: 'field', relation: '时间字段' }
  ].filter(item => semanticGraphText(item.key, item.label))

  fieldNodes.forEach((item, index) => {
    const key = `field:${semanticGraphText(item.key, item.label, index)}`
    const display = item.label && item.key && item.label !== item.key ? `${item.label} (${item.key})` : semanticGraphText(item.label, item.key)
    addNode(key, display, item.type)
    if (tableKey) addEdge(tableKey, key, item.relation || '包含字段')
  })

  evidence.slice(0, 8).forEach((item, index) => {
    const label = semanticGraphText(item?.label, item?.fieldDisplayName, item?.field, item?.role, `语义依据${index + 1}`)
    const key = `evidence:${semanticGraphText(item?.field, item?.label, index)}`
    addNode(key, label, item?.fieldType || item?.semanticAction || 'semantic', {
      content: semanticGraphText(item?.reason, item?.formula, item?.expression, item?.source)
    })
    if (tableKey) addEdge(tableKey, key, '命中依据')
    const fieldKey = semanticGraphText(item?.field)
    if (fieldKey) {
      const target = `field:${fieldKey}`
      if (nodeMap.has(target)) addEdge(key, target, '引用字段')
    }
  })

  if (tableKey && !rawGraphEdges.length) {
    for (const node of nodes) {
      if (node.id !== tableKey) addEdge(tableKey, node.id, node.category === 1 ? '包含字段' : '关联')
    }
  } else if (!edges.length && nodes.length > 1) {
    const root = nodes[0].id
    nodes.slice(1).forEach(node => addEdge(root, node.id, '关联'))
  }

  return {
    nodes,
    edges,
    sourceLabel: rawGraphNodes.length
      ? '来自 GraphRAG 本次召回路径'
      : '由本次命中的字段与语义依据生成'
  }
}

const semanticKnowledgeGraphData = computed(() => buildSemanticKnowledgeGraph(lastAnalysis?.value || {}))
const semanticKnowledgeGraphVisible = computed(() => semanticKnowledgeGraphData.value.nodes.length > 1)
const semanticKnowledgeGraphSignature = computed(() => JSON.stringify({
  nodes: semanticKnowledgeGraphData.value.nodes.map(node => [node.id, node.name, node.category]),
  edges: semanticKnowledgeGraphData.value.edges.map(edge => [edge.source, edge.target, edge.label])
}))

const normalizeAuditList = (value) => {
  if (Array.isArray(value)) {
    return value.map(item => String(item || '').trim()).filter(Boolean)
  }
  const text = String(value || '').trim()
  if (!text) return []
  return text.split(/[,，、;；|]/).map(item => item.trim()).filter(Boolean)
}

const analysisSensitiveFields = computed(() => normalizeAuditList(lastAnalysis?.value?.sensitiveFields))

const parseDiagnosisPreviewJson = (value) => {
  if (!value || typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

const diagnosisPreviewConversationId = (report) => {
  if (!report || typeof report !== 'object') return ''
  const binding = parseDiagnosisPreviewJson(report.bindingJson) || {}
  const snapshot = parseDiagnosisPreviewJson(report.chartSnapshot) || {}
  return String(
    report.conversationId
    || binding.conversationId
    || snapshot.conversationId
    || ''
  ).trim()
}

const diagnosisPreviewBelongsToActiveSession = (report) => {
  const activeId = String(activeChatSessionId?.value || '').trim()
  const reportConversationId = diagnosisPreviewConversationId(report)
  return Boolean(activeId && reportConversationId && activeId === reportConversationId)
}

const diagnosisPreviewReport = computed(() => {
  const report = currentDiagnosis?.value || null
  return diagnosisPreviewBelongsToActiveSession(report) ? report : null
})

const diagnosisPreviewHasReport = computed(() => Boolean(diagnosisPreviewReport.value))

const diagnosisPreviewKicker = computed(() =>
  diagnosisPreviewHasReport.value ? '最新诊断报告预览' : '智能诊断报告预览'
)

const diagnosisPreviewTitle = computed(() => {
  if (diagnosisPreviewHasReport.value) {
    return String(diagnosisPreviewReport.value?.title || '').trim() || '智能诊断报告'
  }
  if (lastAnalysis?.value) {
    return '当前查询可生成诊断报告'
  }
  return '暂无可诊断查询'
})

const diagnosisPreviewCreatedAt = computed(() => {
  const raw = String(diagnosisPreviewReport.value?.createdAt || '').trim()
  if (!raw) return diagnosisPreviewHasReport.value ? '生成时间待记录' : '等待生成'
  return raw.slice(0, 19).replace('T', ' ')
})

const diagnosisPreviewSummary = computed(() =>
  String(diagnosisPreviewReport.value?.summary || '').trim()
  || (lastAnalysis?.value
    ? '完成查询后可基于当前图表、字段映射、SQL 审计和 GraphRAG 证据生成智能诊断报告。'
    : '请先完成一次对话查询，系统会在这里展示可诊断内容。')
)

const diagnosisPreviewTable = computed(() =>
  String(diagnosisPreviewReport.value?.tableName || lastAnalysis?.value?.tableName || '').trim()
)

const diagnosisPreviewMetric = computed(() =>
  String(
    diagnosisPreviewReport.value?.metricFieldLabel
    || diagnosisPreviewReport.value?.metricLabel
    || diagnosisPreviewReport.value?.metricField
    || lastAnalysis?.value?.fieldMapping?.metric
    || lastAnalysis?.value?.fieldMapping?.metricKey
    || ''
  ).trim()
)

const diagnosisPreviewCount = (value) => Array.isArray(value) ? value.length : 0

const diagnosisPreviewCanGenerate = computed(() =>
  Boolean(lastAnalysis?.value && canDiagnoseLastAnalysis?.value !== false)
)

const diagnosisPreviewActionText = computed(() =>
  diagnosisPreviewHasReport.value ? '查看完整报告' : '一键生成诊断报告'
)

const diagnosisPreviewStats = computed(() => {
  const report = diagnosisPreviewReport.value || {}
  if (!diagnosisPreviewHasReport.value) {
    const rowCount = Array.isArray(lastAnalysis?.value?.data) ? lastAnalysis.value.data.length : 0
    return [
      { label: '查询结果', value: rowCount, icon: TrendCharts },
      { label: '敏感字段', value: analysisSensitiveFields.value.length, icon: Files },
      { label: '语义依据', value: semanticEvidenceItems.value.length, icon: DataAnalysis }
    ]
  }
  const evidenceCount = diagnosisPreviewCount(report.graphRagEvidenceChain)
    + diagnosisPreviewCount(report.docEvidence)
    + diagnosisPreviewCount(report.relatedKnowledge)
  return [
    { label: '异常节点', value: diagnosisPreviewCount(report.anomalyMarkers), icon: TrendCharts },
    { label: '证据命中', value: evidenceCount, icon: Files },
    { label: '推理步骤', value: diagnosisPreviewCount(report.reasoningLogs), icon: DataAnalysis }
  ]
})

const handleDiagnosisPreviewAction = () => {
  if (diagnosisPreviewHasReport.value) {
    activeModule.value = 'diagnosis'
    return
  }
  if (diagnosisPreviewCanGenerate.value) {
    diagnoseFromLastAnalysis()
  }
}

const graphContextFallbackItems = computed(() =>
  semanticEvidenceItems.value.length
    ? []
    : (Array.isArray(lastAnalysis?.value?.graphContext) ? lastAnalysis.value.graphContext : [])
)

const semanticEvidencePanelTitle = computed(() =>
  semanticEvidenceItems.value.length ? '本次语义依据' : '语义依据'
)

const semanticEvidencePanelDescription = computed(() => {
  if (semanticEvidenceItems.value.length) {
    return '仅展示本次查询真正命中的字段、指标、维度和时间口径。'
  }
  if (graphContextFallbackItems.value.length) {
    return '本次未形成精确语义依据，已保留 GraphRAG 候选上下文供核验。'
  }
  return '本次查询没有可解释的业务模型、GraphRAG 或字段映射依据。'
})

const currentChatSession = computed(() =>
  (chatSessions?.value || []).find(item => String(item?.id || '') === String(activeChatSessionId?.value || '')) || null
)

const refreshBusinessDictionaryPanel = async () => {
  try {
    await loadBusinessModels?.()
  } catch (error) {
    console.warn('refresh business models on drawer open failed:', error)
  }
}

const formatBoundFieldValue = (item = {}) => {
  const fieldName = String(item?.field || '').trim()
  const displayName = String(item?.fieldDisplayName || '').trim() || fieldLabel(fieldName)
  if (displayName && fieldName && displayName !== fieldName) {
    return `${displayName}（${fieldName}）`
  }
  return fieldName || displayName
}

const formatFieldBindingResultValue = (item = {}) => {
  const semanticAction = String(item?.semanticAction || '').trim().toUpperCase()
  const formula = String(item?.formula || '').trim()
  const boundField = formatBoundFieldValue(item)
  if (semanticAction === 'METRIC_FORMULA_UPDATE' || semanticAction === 'METRIC_SCOPE_UPDATE') {
    return formula || boundField || '已更新'
  }
  return boundField || formula || '未绑定成功'
}

const chatContentMode = ref('messages')
const advancedHistoryVisible = ref(false)
const advancedAnalysisHistory = ref([])
const advancedAlertRules = ref([])
const advancedAlertRulesLoading = ref(false)
const advancedAlertEvents = ref([])
const advancedAlertEventsLoading = ref(false)
const alertRuleEditorVisible = ref(false)
const alertRuleEditorSaving = ref(false)
const alertRuleEditorMeta = ref({ timeFields: [], numericFields: [] })
const alertRuleEditorForm = ref({
  id: '',
  tableName: '',
  timeField: '',
  metricField: '',
  filterExpression: '',
  granularity: 'day',
  operator: 'lt',
  threshold: 100000,
  detectionCycle: 'daily',
  channels: ['email', 'dingtalk']
})
const advancedAnalysisDialogVisible = ref(false)
const activeAdvancedAnalysis = ref(null)
const advancedAnalysisExplainingId = ref('')
const forecastConfirmVisible = ref(false)
const forecastConfirmMeta = ref({ timeFields: [], numericFields: [] })
const forecastConfirmForm = ref({
  tableName: '',
  timeField: '',
  metricField: '',
  granularity: 'month',
  horizon: 3,
  algorithm: 'Holt-Winters',
  alpha: 0.55,
  beta: 0.28,
  gamma: 0.20,
  seasonLength: 0
})
let forecastConfirmResolver = null
const whatIfConfirmVisible = ref(false)
const whatIfConfirmMeta = ref({ numericFields: [] })
const whatIfConfirmForm = ref({
  targetMetric: '',
  formula: '',
  variables: []
})
let whatIfConfirmResolver = null
const alertConfirmVisible = ref(false)
const alertConfirmMeta = ref({ timeFields: [], numericFields: [] })
const alertConfirmForm = ref({
  tableName: '',
  timeField: '',
  metricField: '',
  filterExpression: '',
  granularity: 'day',
  operator: 'lt',
  threshold: 100000,
  detectionCycle: 'daily',
  channels: ['email', 'dingtalk']
})
let alertConfirmResolver = null
const savingAlertDraftKey = ref('')

const normalizeMultiStepList = (value) => Array.isArray(value) ? value.filter(item => item && typeof item === 'object') : []

const isAlertRuleMultiStepAction = (step = {}) => {
  const type = String(step?.type || step?.intent || step?.smartIntent || '').trim().toUpperCase()
  return ['ALERT_RULE_CREATE_DRAFT', 'ALERT_RULE_CREATE', 'ALERT_RULE_DRAFT'].includes(type)
}

const buildCreatedAlertStepPatch = (analysis = {}, savedRule = {}) => ({
  status: 'COMPLETED',
  message: savedRule?.id
    ? `预警规则已创建，规则 #${savedRule.id} 已进入离线检测。`
    : '预警规则已创建，后续将按检测周期离线检测。',
  requiresConfirmation: false,
  ruleId: savedRule?.id || analysis?.ruleId || analysis?.params?.ruleId || null,
  alertRuleCreated: {
    id: savedRule?.id || analysis?.ruleId || null,
    title: analysis?.title || savedRule?.ruleName || '预警规则',
    metricField: analysis?.params?.metricField || savedRule?.metricField || '',
    timeField: analysis?.params?.timeField || savedRule?.timeField || '',
    operator: analysis?.params?.operator || savedRule?.operator || '',
    threshold: analysis?.params?.threshold ?? savedRule?.threshold,
    channels: analysis?.params?.channels || savedRule?.channels || []
  }
})

const markAlertStepsCreated = (steps = [], analysis = {}, savedRule = {}) => {
  const patch = buildCreatedAlertStepPatch(analysis, savedRule)
  return normalizeMultiStepList(steps).map(step => {
    if (!isAlertRuleMultiStepAction(step)) return step
    return {
      ...step,
      ...patch
    }
  })
}

const rebuildMultiStepSummary = (message = {}, nextStepResults = [], nextActions = []) => {
  const base = message?.multiStepSummary && typeof message.multiStepSummary === 'object' ? message.multiStepSummary : {}
  const steps = normalizeMultiStepList(nextStepResults).length ? normalizeMultiStepList(nextStepResults) : normalizeMultiStepList(nextActions)
  const countByStatus = (status) => steps.filter(step => String(step.status || '').trim().toUpperCase() === status).length
  const total = Number(base.total ?? steps.length ?? 0)
  return {
    ...base,
    total,
    completed: countByStatus('COMPLETED'),
    needsConfirmation: countByStatus('NEEDS_CONFIRMATION'),
    failed: countByStatus('FAILED'),
    skipped: countByStatus('SKIPPED')
  }
}

const buildAlertCreatedMultiStepPatch = (message = {}, analysis = {}, savedRule = {}) => {
  const nextStepResults = markAlertStepsCreated(message?.stepResults, analysis, savedRule)
  const rawActionPlan = message?.actionPlan && typeof message.actionPlan === 'object' ? message.actionPlan : null
  const nextActions = rawActionPlan?.actions
    ? markAlertStepsCreated(rawActionPlan.actions, analysis, savedRule)
    : []
  const summary = rebuildMultiStepSummary(message, nextStepResults, nextActions)
  const hasPendingConfirmation = [...nextStepResults, ...nextActions]
    .some(step => String(step?.status || '').trim().toUpperCase() === 'NEEDS_CONFIRMATION')
  const patch = {
    multiStepSummary: summary,
    requiresConfirmation: hasPendingConfirmation
  }
  if (nextStepResults.length) {
    patch.stepResults = nextStepResults
  }
  if (rawActionPlan) {
    patch.actionPlan = {
      ...rawActionPlan,
      requiresConfirmation: hasPendingConfirmation,
      actions: nextActions.length ? nextActions : rawActionPlan.actions
    }
  }
  return patch
}

const normalizedMultiStepActions = (msg = {}) => {
  const planActions = normalizeMultiStepList(msg?.actionPlan?.actions)
  const stepResults = normalizeMultiStepList(msg?.stepResults)
  const stepById = new Map(stepResults.map(step => [String(step.id || ''), step]))
  const source = planActions.length ? planActions : stepResults
  return source.map((action, index) => {
    const id = String(action.id || action.stepId || `step_${index + 1}`)
    const matched = stepById.get(id) || {}
    const dependsOn = Array.isArray(action.dependsOn)
      ? action.dependsOn
      : (Array.isArray(matched.dependsOn) ? matched.dependsOn : [])
    return {
      ...action,
      ...matched,
      id,
      type: String(matched.type || action.type || action.intent || '').trim(),
      question: String(action.question || matched.question || '').trim(),
      message: String(matched.message || action.message || '').trim(),
      status: String(matched.status || action.status || '').trim(),
      dependsOn: dependsOn.map(item => String(item || '').trim()).filter(Boolean),
      confidence: matched.confidence ?? action.confidence
    }
  }).filter(step => step.type || step.status || step.message)
}

const hasMultiStepPlan = (msg = {}) => {
  const primaryIntent = String(msg?.actionPlan?.primaryIntent || msg?.smartIntent || msg?.responseType || '').toUpperCase()
  return primaryIntent === 'MULTI_STEP' || normalizedMultiStepActions(msg).length > 1 || Boolean(msg?.multiStepSummary)
}

const multiStepActionTypeLabel = (type) => {
  const value = String(type || '').trim().toUpperCase()
  if (value === 'QUERY_SQL') return '数据查询'
  if (value === 'FORECAST' || value === 'ADVANCED_FORECAST') return '时序预测'
  if (value === 'ALERT_RULE_CREATE_DRAFT' || value === 'ALERT_RULE_CREATE') return '预警草稿'
  if (value === 'DASHBOARD_PIN') return '钉入看板'
  if (value === 'CLARIFY' || value === 'CLARIFICATION') return '补充确认'
  return value || '智能动作'
}

const multiStepStatusLabel = (status) => {
  const value = String(status || '').trim().toUpperCase()
  if (value === 'COMPLETED') return '已完成'
  if (value === 'NEEDS_CONFIRMATION') return '待确认'
  if (value === 'NEEDS_INPUT') return '待补充'
  if (value === 'FAILED') return '失败'
  if (value === 'SKIPPED') return '已跳过'
  return value || '处理中'
}

const multiStepStatusTagType = (status) => {
  const value = String(status || '').trim().toUpperCase()
  if (value === 'COMPLETED') return 'success'
  if (value === 'NEEDS_CONFIRMATION' || value === 'NEEDS_INPUT') return 'warning'
  if (value === 'FAILED') return 'danger'
  if (value === 'SKIPPED') return 'info'
  return 'primary'
}

const multiStepStatusClass = (status) => {
  const value = String(status || '').trim().toUpperCase()
  if (value === 'COMPLETED') return 'completed'
  if (value === 'NEEDS_CONFIRMATION' || value === 'NEEDS_INPUT') return 'pending'
  if (value === 'FAILED') return 'failed'
  if (value === 'SKIPPED') return 'skipped'
  return 'running'
}

const formatMultiStepConfidence = (value) => {
  if (value == null || value === '') return ''
  if (typeof value === 'string' && value.includes('%')) return value
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return ''
  return `${Math.round((number <= 1 ? number * 100 : number))}%`
}

const multiStepComputedSummary = (msg = {}) => {
  const actions = normalizedMultiStepActions(msg)
  const summary = msg?.multiStepSummary && typeof msg.multiStepSummary === 'object' ? msg.multiStepSummary : {}
  const countByStatus = (status) => actions.filter(step => String(step.status || '').toUpperCase() === status).length
  return {
    total: Number(summary.total ?? actions.length ?? 0),
    completed: Number(summary.completed ?? countByStatus('COMPLETED')),
    needsConfirmation: Number(summary.needsConfirmation ?? countByStatus('NEEDS_CONFIRMATION')),
    failed: Number(summary.failed ?? countByStatus('FAILED')),
    skipped: Number(summary.skipped ?? countByStatus('SKIPPED'))
  }
}

const multiStepSummaryValue = (msg, key) => {
  const value = Number(multiStepComputedSummary(msg)[key] || 0)
  return Number.isFinite(value) ? value : 0
}

const multiStepSummaryLabel = (msg = {}) => {
  const summary = multiStepComputedSummary(msg)
  if (summary.failed || summary.skipped) return '部分完成'
  if (summary.needsConfirmation) return '待确认'
  if (summary.total && summary.completed >= summary.total) return '已完成'
  return '执行中'
}

const multiStepSummaryTagType = (msg = {}) => {
  const label = multiStepSummaryLabel(msg)
  if (label === '已完成') return 'success'
  if (label === '待确认') return 'warning'
  if (label === '部分完成') return 'danger'
  return 'primary'
}

const multiStepActionChain = (msg = {}) => {
  const labels = normalizedMultiStepActions(msg).map(step => multiStepActionTypeLabel(step.type)).filter(Boolean)
  return labels.length ? labels.join(' → ') : '复合任务'
}

const multiStepMissingSlots = (msg = {}) => {
  const slots = Array.isArray(msg?.actionPlan?.missingSlots) ? msg.actionPlan.missingSlots : []
  return slots.map(item => String(item || '').trim()).filter(Boolean)
}

const advancedAnalysisTypeLabel = (type) => {
  if (type === 'forecast') return '时序预测'
  if (type === 'whatIf') return 'What-if 推演'
  if (type === 'alert') return '离线智能预警'
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

const defaultAdvancedChartRecommendation = (type) => {
  if (type === 'forecast') {
    return {
      ruleCode: 'time_series_default',
      ruleName: '时序趋势默认规则',
      scenarioType: 'TIME_SERIES',
      status: 'CONFIGURED',
      explain: '识别到预测类时序分析，推荐折线图展示历史值、预测值和 95% 置信区间。'
    }
  }
  if (type === 'whatIf') {
    return {
      ruleCode: 'advanced_whatif_compare',
      ruleName: '情景推演对比规则',
      scenarioType: 'GROUP_COMPARE',
      status: 'EXTENDED',
      explain: '识别到情景推演分析，推荐柱状图对比基准、保守、中性、乐观和推荐方案。'
    }
  }
  if (type === 'alert') {
    return {
      ruleCode: 'advanced_alert_line',
      ruleName: '智能预警趋势规则',
      scenarioType: 'TIME_SERIES',
      status: 'EXTENDED',
      explain: '识别到预警检测场景，推荐折线图展示检测值、阈值线和异常波动。'
    }
  }
  return {
    ruleCode: 'advanced_analysis_default',
    ruleName: '高级分析默认规则',
    scenarioType: 'CUSTOM',
    status: 'EXTENDED',
    explain: '高级分析结果已按业务场景自动匹配展示方式。'
  }
}

const advancedRuleInfo = (analysis = {}) => {
  const recommendation = analysis?.chartRecommendation && typeof analysis.chartRecommendation === 'object'
    ? analysis.chartRecommendation
    : {}
  const ruleCode = String(analysis?.chartRuleCode || recommendation.ruleCode || '').trim()
  const ruleName = String(analysis?.chartRuleName || recommendation.ruleName || '').trim()
  const scenarioType = String(analysis?.chartScenarioType || recommendation.scenarioType || '').trim()
  const status = String(analysis?.chartRecommendationStatus || recommendation.status || '').trim()
  const explain = formatChartRecommendationExplain(
    analysis?.chartRecommendationExplain || recommendation.explain,
    { ruleCode, ruleName, scenarioType, status }
  )
  return {
    has: Boolean(ruleCode || ruleName || scenarioType || explain),
    ruleCode,
    ruleName,
    scenarioType,
    scenarioLabel: advancedScenarioLabel(scenarioType),
    status,
    explain
  }
}

const withAdvancedChartRecommendation = (analysis = {}) => {
  if (!analysis || typeof analysis !== 'object') return analysis
  const fallback = defaultAdvancedChartRecommendation(analysis.type)
  const current = analysis.chartRecommendation && typeof analysis.chartRecommendation === 'object'
    ? analysis.chartRecommendation
    : {}
  const rawExplain = analysis.chartRecommendationExplain || current.explain || fallback.explain
  const recommendation = {
    ...fallback,
    ...current,
    ruleCode: String(analysis.chartRuleCode || current.ruleCode || fallback.ruleCode || '').trim(),
    ruleName: String(analysis.chartRuleName || current.ruleName || fallback.ruleName || '').trim(),
    scenarioType: String(analysis.chartScenarioType || current.scenarioType || fallback.scenarioType || '').trim(),
    status: String(analysis.chartRecommendationStatus || current.status || fallback.status || '').trim()
  }
  recommendation.explain = formatChartRecommendationExplain(rawExplain, recommendation)
  return {
    ...analysis,
    chartRecommendation: recommendation,
    chartRuleCode: recommendation.ruleCode,
    chartRuleName: recommendation.ruleName,
    chartScenarioType: recommendation.scenarioType,
    chartRecommendationStatus: recommendation.status,
    chartRecommendationExplain: recommendation.explain
  }
}

const formatAdvancedNumber = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return '--'
  if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(1)}万`
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

const formatAdvancedFieldLabel = (field) => {
  const displayName = String(field?.displayName || field?.businessName || '').trim()
  const columnName = String(field?.columnName || '').trim()
  if (displayName && columnName && displayName !== columnName) {
    return `${displayName}（${columnName}）`
  }
  return displayName || columnName || '未命名字段'
}

const formatAnalysisMetricLabel = (fieldName, fallback = '指标', fields = []) => {
  const field = String(fieldName || '').trim()
  if (!field) return fallback
  const matched = Array.isArray(fields)
    ? fields.find(item => String(item?.columnName || '').trim() === field)
    : null
  if (matched) {
    return formatAdvancedFieldLabel(matched)
  }
  const prettified = field
    .replace(/^col[_-]?/i, '')
    .replace(/[_-]+/g, ' ')
    .trim()
  if (!prettified) return fallback
  return /[A-Za-z]/.test(prettified) ? prettified.toUpperCase() : prettified
}

const inferAdvancedIntent = (text) => {
  const content = String(text || '').trim().toLowerCase()
  if (!content) return ''
  if (hasCompositeForecastSemantic(content) || /prophet|holt/.test(content)) return 'forecast'
  if (/what-?if|如果|若|假设|提升|下降|降低|增长|推演|模拟|利润变化/.test(content)) return 'whatIf'
  if (/预警|提醒|告警|低于|高于|超过|异常|阈值|通知|钉钉|邮件|z-?score/.test(content)) return 'alert'
  return ''
}

const hasCompositeQuerySemantic = (text = '') => {
  const content = String(text || '').trim()
  return /查|看|查看|查询|统计|分析|展示|给我看|画/.test(content) ||
    /趋势|走势|排名|排行|对比|分布|明细|各省|各市|各区域/.test(content)
}

const hasCompositeForecastSemantic = (text = '') => /预测|预估|推算|未来|后面|往后|下个月|下季度|下一季度|后续|大概会|会到多少|继续涨|继续跌|趋势延伸|forecast|prediction/i.test(String(text || ''))

const hasCompositeAlertSemantic = (text = '') => /预警|提醒|告警|警报|通知|低于|高于|超过|跌破|阈值|异常|邮件|钉钉|alert|warning/i.test(String(text || ''))

const hasCompositeConnectorSemantic = (text = '') => /并|然后|再|同时|顺便|接着|之后|并且|如果|若|假设/.test(String(text || ''))

const shouldUseSmartMultiStepOrchestration = (text = '') => {
  const content = String(text || '').trim()
  if (!content) return false
  if (!hasCompositeForecastSemantic(content)) return false
  const hasQuery = hasCompositeQuerySemantic(content)
  const hasAlert = hasCompositeAlertSemantic(content)
  if (hasAlert && /如果|低于|高于|超过|跌破|提醒|预警|告警|通知/.test(content)) return true
  if (hasQuery && hasCompositeConnectorSemantic(content)) return true
  return hasQuery && /查|看|查看|查询|统计|分析|展示/.test(content)
}

const inferMetricFromQuestion = (text) => {
  const content = String(text || '')
  const candidates = ['销售额', '利润', '成本', '销量', '收入', '转化率', '退货率', '客单价']
  return candidates.find(item => content.includes(item)) || String(lastAnalysis?.value?.fieldMapping?.metric || '').trim() || '核心指标'
}

const normalizeForecastGranularity = (value = '') => {
  const text = String(value || '').trim().toLowerCase()
  if (['day', 'daily', '按日', '日'].includes(text)) return 'day'
  if (['week', 'weekly', '按周', '周'].includes(text)) return 'week'
  if (['quarter', 'quarterly', '按季度', '季度'].includes(text)) return 'quarter'
  if (['year', 'yearly', '按年', '年'].includes(text)) return 'year'
  return 'month'
}

const inferForecastGranularity = (text) => {
  const content = String(text || '')
  if (/每(日|天)|按日|日度|逐日/.test(content)) return 'day'
  if (/每周|按周|周度|逐周/.test(content)) return 'week'
  if (/每月|按月|月度|逐月/.test(content)) return 'month'
  if (/每季度|按季度|季度粒度|逐季/.test(content)) return 'quarter'
  if (/每年|按年|年度|逐年/.test(content)) return 'year'
  const range = explicitForecastRange(content)
  if (range && ['day', 'week', 'quarter', 'year'].includes(range.unit)) return range.unit
  return 'month'
}

const forecastChineseNumber = (value) => {
  const text = String(value || '').trim()
  if (!text) return 0
  const numeric = Number(text)
  if (Number.isFinite(numeric)) return numeric
  const digits = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 }
  if (text === '十') return 10
  if (digits[text]) return digits[text]
  if (text.startsWith('十')) return 10 + forecastChineseNumber(text.slice(1))
  if (text.endsWith('十')) return forecastChineseNumber(text.slice(0, -1)) * 10
  if (text.includes('十')) {
    const [left, right] = text.split('十')
    return forecastChineseNumber(left) * 10 + forecastChineseNumber(right)
  }
  return 0
}

const normalizeForecastRangeUnit = (unit = '') => {
  const text = String(unit || '')
  if (text.includes('年')) return 'year'
  if (text.includes('季')) return 'quarter'
  if (text.includes('周')) return 'week'
  if (text.includes('天') || text.includes('日')) return 'day'
  return 'month'
}

const explicitForecastRange = (text = '') => {
  const content = String(text || '').trim()
  if (!content) return null
  if (/下个月|未来一个月|后面一个月|往后一个月/.test(content)) return { value: 1, unit: 'month' }
  if (/下季度|下一季度|未来一个季度|后面一个季度/.test(content)) return { value: 1, unit: 'quarter' }
  if (/半年|六个月/.test(content)) return { value: 6, unit: 'month' }
  const match = content.match(/(?:未来|后面|往后|之后|接下来|下|后续)?\s*([一二两三四五六七八九十百\d]+)\s*(个)?\s*(月|个月|季度|季|年|天|日|周)/)
  if (!match) return null
  const value = forecastChineseNumber(match[1])
  return value > 0 ? { value, unit: normalizeForecastRangeUnit(match[3]) } : null
}

const forecastRangeDays = (range) => {
  if (!range) return 0
  if (range.unit === 'day') return range.value
  if (range.unit === 'week') return range.value * 7
  if (range.unit === 'quarter') return range.value * 91
  if (range.unit === 'year') return range.value * 365
  return range.value * 30
}

const horizonByGranularity = (range, granularity = 'month') => {
  const normalized = normalizeForecastGranularity(granularity)
  if (!range) return defaultForecastHorizon(normalized)
  if (range.unit === normalized) return Math.max(1, range.value)
  if (normalized === 'month') {
    if (range.unit === 'quarter') return range.value * 3
    if (range.unit === 'year') return range.value * 12
    if (range.unit === 'week') return Math.max(1, Math.ceil(range.value * 7 / 30))
    if (range.unit === 'day') return Math.max(1, Math.ceil(range.value / 30))
    return Math.max(1, range.value)
  }
  if (normalized === 'quarter') {
    if (range.unit === 'month') return Math.max(1, Math.ceil(range.value / 3))
    if (range.unit === 'year') return range.value * 4
    if (range.unit === 'week') return Math.max(1, Math.ceil(range.value * 7 / 91))
    if (range.unit === 'day') return Math.max(1, Math.ceil(range.value / 91))
    return Math.max(1, range.value)
  }
  if (normalized === 'year') {
    if (range.unit === 'month') return Math.max(1, Math.ceil(range.value / 12))
    if (range.unit === 'quarter') return Math.max(1, Math.ceil(range.value / 4))
    if (range.unit === 'week') return Math.max(1, Math.ceil(range.value * 7 / 365))
    if (range.unit === 'day') return Math.max(1, Math.ceil(range.value / 365))
    return Math.max(1, range.value)
  }
  const days = forecastRangeDays(range)
  if (normalized === 'day') return Math.max(1, days)
  if (normalized === 'week') return Math.max(1, Math.ceil(days / 7))
  return Math.max(1, range.value)
}

const defaultForecastHorizon = (granularity = 'month') => {
  const normalized = normalizeForecastGranularity(granularity)
  if (normalized === 'day') return 30
  if (normalized === 'week') return 12
  if (normalized === 'quarter') return 4
  if (normalized === 'year') return 3
  return 3
}

const forecastGranularityUnitLabel = (granularity = 'month') => {
  const normalized = normalizeForecastGranularity(granularity)
  if (normalized === 'day') return '天'
  if (normalized === 'week') return '周'
  if (normalized === 'quarter') return '个季度'
  if (normalized === 'year') return '年'
  return '个月'
}

const forecastTimeRangeLabel = (horizon, granularity = 'month') =>
  `未来 ${normalizeHorizonCount(horizon, granularity)} ${forecastGranularityUnitLabel(granularity)}`

const inferForecastHorizon = (text, granularity = inferForecastGranularity(text)) => {
  const range = explicitForecastRange(text)
  const count = range
    ? horizonByGranularity(range, granularity)
    : defaultForecastHorizon(granularity)
  return Math.max(1, Math.min(count, 60))
}

const inferAlertThreshold = (text) => {
  const content = String(text || '')
  const match = content.match(/(\d+(?:\.\d+)?)\s*(万|千|k|w)?/)
  if (!match) return 100000
  const raw = Number(match[1])
  if (!Number.isFinite(raw)) return 100000
  const unit = String(match[2] || '').toLowerCase()
  if (unit === '万' || unit === 'w') return raw * 10000
  if (unit === '千' || unit === 'k') return raw * 1000
  return raw
}

const alertCycleLabel = (cycle = 'daily') => {
  const labels = {
    hourly: '每小时检测',
    daily: '每日检测',
    weekly: '每周检测',
    monthly: '每月检测'
  }
  return labels[cycle] || '每日检测'
}

const formatAlertChannel = (channels = []) => {
  const values = Array.isArray(channels) ? channels : [channels]
  const labels = values.map(item => {
    if (item === 'email') return '邮件'
    if (item === 'dingtalk') return '钉钉'
    if (item === 'both') return '邮件 + 钉钉'
    return ''
  }).filter(Boolean)
  return labels.length ? [...new Set(labels)].join(' + ') : '邮件 + 钉钉'
}

const formatAlertRuleTitle = (rule = {}) => {
  const metric = String(rule.metricField || '指标').trim()
  const operatorMap = { lt: '低于', gt: '高于', zscore: '异常波动' }
  const operator = operatorMap[rule.operator] || '触发'
  const threshold = rule.operator === 'zscore' ? 'Z-Score' : formatAdvancedNumber(rule.threshold)
  return `${metric} ${operator} ${threshold}`
}

const formatChatAlertDraftTitle = (rule = {}) => {
  const metric = fieldLabel(rule.metricField) || String(rule.metricField || '指标').trim()
  const operatorMap = { lt: '低于', gt: '高于', zscore: '异常波动' }
  const operator = operatorMap[String(rule.operator || 'lt').toLowerCase()] || '触发'
  const threshold = String(rule.operator || '').toLowerCase() === 'zscore'
    ? 'Z-Score'
    : formatAdvancedNumber(rule.threshold)
  return `${metric || '指标'} ${operator} ${threshold}`
}

const alertDraftKey = (rule = {}) => [
  rule.tableName,
  rule.metricField,
  rule.timeField,
  rule.operator,
  rule.threshold
].map(item => String(item ?? '').trim()).join('|')

const formatAlertRuleMeta = (rule = {}) => {
  const parts = [
    rule.tableName,
    alertCycleLabel(rule.detectionCycle),
    formatAlertChannel(rule.channels),
    rule.filterExpression ? `过滤：${rule.filterExpression}` : ''
  ].filter(Boolean)
  return parts.join(' / ')
}

const buildForecastCardExplanation = ({ algorithm, historyPoints, forecastPoints, lastForecast, source = '当前上下文' } = {}) => ({
  source: 'rule',
  sourceLabel: '规则解释',
  calculation: [
    `当前使用${algorithm || '预测算法'}生成预测曲线，数据来源为${source}。`,
    historyPoints ? `历史序列包含 ${historyPoints} 个有效点，向前预测 ${forecastPoints || 0} 个点。` : '',
    lastForecast != null ? `末期预测值为 ${formatAdvancedNumber(lastForecast)}。` : ''
  ].filter(Boolean),
  suggestions: [
    '请同时关注预测值和置信区间，上下界差距越大代表未来不确定性越高。',
    '若数据点偏少或近期波动较大，建议补充更长周期数据后重新计算。'
  ]
})

const buildWhatIfCardExplanation = ({ base, scenario, recommended, variables = [], formula = '' } = {}) => {
  const delta = base ? ((Number(scenario || 0) - Number(base || 0)) / Math.abs(Number(base || 0))) * 100 : 0
  const topVariable = Array.isArray(variables) ? variables[0] : null
  return {
    source: 'rule',
    sourceLabel: '规则解释',
    calculation: [
      base != null ? `基准方案为 ${formatAdvancedNumber(base)}，中性方案为 ${formatAdvancedNumber(scenario)}。` : '当前基于变量变化生成多场景推演结果。',
      formula ? `本次推演使用业务公式「${formula}」计算目标结果。` : '',
      recommended != null ? `推荐方案为 ${formatAdvancedNumber(recommended)}，中性方案相对基准变化 ${delta >= 0 ? '+' : ''}${delta.toFixed(2)}%。` : '',
      topVariable ? `变量「${topVariable.name || topVariable.field || '变量'}」参与本次推演，建议结合敏感性排序判断优先级。` : ''
    ].filter(Boolean),
    suggestions: [
      '优先评估推荐方案在预算、库存、交付和合规上的可执行性。',
      formula ? '请确认公式字段单位、聚合方式和业务口径一致。' : '推演结果用于方案比较，不等同于因果结论，落地前建议结合业务公式或实验数据校验。'
    ]
  }
}

const buildAlertCardExplanation = ({ operator = 'lt', threshold, channels = [], detectionCycle = 'daily' } = {}) => {
  const operatorText = operator === 'gt' ? '高于阈值' : operator === 'zscore' ? 'Z-Score 异常波动' : '低于阈值'
  return {
    source: 'rule',
    sourceLabel: '规则解释',
    calculation: [
      `当前预警规则采用${operatorText}判断，检测周期为${alertCycleLabel(detectionCycle)}。`,
      `阈值配置为 ${operator === 'zscore' ? 'Z-Score >= 3' : formatAdvancedNumber(threshold)}。`,
      `通知渠道为 ${formatAlertChannel(channels)}。`
    ],
    suggestions: [
      '触发预警后建议先核对异常时段原始数据，再判断是否为真实业务波动。',
      '若误报较多，可调整阈值、过滤条件或检测粒度后重新保存规则。'
    ]
  }
}

const formatAlertEventTitle = (event = {}) => {
  const value = formatAdvancedNumber(event.actualValue)
  return `规则 #${event.ruleId || '-'} / ${event.bucketName || '-'} / 实际值 ${value}`
}

const inferWhatIfVariables = (text) => {
  const content = String(text || '')
  const matches = [...content.matchAll(/([\u4e00-\u9fa5A-Za-z]+?)(提升|增长|上涨|下降|降低|减少)\s*(\d+(?:\.\d+)?)\s*%/g)]
  const variables = matches.map(match => ({
    name: match[1].replace(/[如果若假设]/g, '').trim() || '变量',
    change: ['下降', '降低', '减少'].includes(match[2]) ? -Number(match[3]) : Number(match[3])
  })).filter(item => item.name && Number.isFinite(item.change))
  return variables.length ? variables : [
    { name: '销量', change: 10 },
    { name: '成本', change: -5 }
  ]
}

const whatIfFormulaFunctionPattern = /\b(?:SAFE_DIVIDE|IF|ABS|MIN|MAX|ROUND|DIVIDE)\s*\(/i

const hasExplicitWhatIfFormulaIntent = (text = '') => {
  const content = normalizeWhatIfFormulaSyntax(text).replace(/\s+/g, ' ').trim()
  if (!content) return false
  if (whatIfFormulaFunctionPattern.test(content)) return true
  if (/(?:业务公式|指标公式|公式|按|按照)\s*[:：]?.+[=]/.test(content)) return true
  if (/(?:按|按照)\s+[\s\S]*[+\-*\/][\s\S]*/.test(content)) return true
  const directMatch = content.match(/([\u4e00-\u9fa5A-Za-z_][\u4e00-\u9fa5A-Za-z0-9_\s]*)=([\s\S]+)$/)
  if (!directMatch) return false
  const rightExpression = directMatch[2] || ''
  return (
    /[+\-*\/()]/.test(rightExpression)
    || /^[+-]?\d+(?:\.\d+)?%?$/.test(rightExpression.trim())
    || whatIfFormulaFunctionPattern.test(rightExpression)
  )
}

const inferWhatIfFormula = (text, llmIntent = {}) => {
  const content = String(text || '').replace(/\s+/g, ' ').trim()
  const hasExplicitFormula = hasExplicitWhatIfFormulaIntent(content)
  const explicit = hasExplicitFormula ? String(llmIntent.formula || llmIntent.businessFormula || '').trim() : ''
  if (explicit) return trimWhatIfFormulaTail(explicit)
  if (!hasExplicitFormula) return ''
  const match = content.match(/(?:公式|按|按照)\s*[:：]?([\s\S]+?[=＝][\s\S]+)$/)
  if (match?.[1]) return trimWhatIfFormulaTail(match[1])
  const directMatch = content.match(/([\u4e00-\u9fa5A-Za-z_][\u4e00-\u9fa5A-Za-z0-9_\s]*)[=＝]([\s\S]+)$/)
  if (directMatch?.[0]) return trimWhatIfFormulaTail(directMatch[0])
  const functionMatch = content.match(/\b(?:SAFE_DIVIDE|IF|ABS|MIN|MAX|ROUND|DIVIDE)\s*\([\s\S]+\)/i)
  if (functionMatch?.[0]) return trimWhatIfFormulaTail(functionMatch[0])
  return ''
}

const buildForecastSeries = (params = {}) => {
  const futureCount = normalizeHorizonCount(params.horizon, params.granularity || 'month', params.sourceQuestion || '')
  const historyCount = 10
  const base = 86000
  const rows = []
  for (let index = 0; index < historyCount; index += 1) {
    const value = Math.round(base + index * 4200 + Math.sin(index / 1.7) * 7600)
    rows.push({
      name: `历史${index + 1}`,
      history: value,
      forecast: null,
      upper: null,
      lower: null
    })
  }
  const lastValue = rows[rows.length - 1].history
  for (let index = 1; index <= futureCount; index += 1) {
    const forecast = Math.round(lastValue * (1 + index * 0.045) + Math.sin(index) * 3800)
    rows.push({
      name: `未来${index}`,
      history: null,
      forecast,
      upper: Math.round(forecast * 1.12),
      lower: Math.round(forecast * 0.88)
    })
  }
  return rows
}

const buildWhatIfSeries = (variables = []) => {
  const base = Number(lastAnalysis?.value?.data?.[0]?.value || 120000)
  const effect = variables.reduce((sum, variable) => {
    const name = String(variable.name || '')
    const change = Number(variable.change || 0)
    const mode = variable.mode || 'percent'
    const normalizedChange = mode === 'absolute'
      ? (base ? change / Math.abs(base) * 100 : change)
      : mode === 'set'
        ? (base ? (change - base) / Math.abs(base) * 100 : change)
        : change
    const weight = /成本|费用/.test(name) ? -0.42 : /价格|客单价/.test(name) ? 0.36 : 0.58
    return sum + normalizedChange * weight
  }, 0)
  const conservative = Math.max(0, Math.round(base * (1 + effect * 0.5 / 100)))
  const scenario = Math.max(0, Math.round(base * (1 + effect / 100)))
  const optimistic = Math.max(0, Math.round(base * (1 + effect * 1.35 / 100)))
  const optimized = Math.round(Math.max(conservative, scenario, optimistic))
  return [
    { name: '基准方案', value: Math.round(base) },
    { name: '保守方案', value: conservative },
    { name: '中性方案', value: scenario },
    { name: '乐观方案', value: optimistic },
    { name: '推荐方案', value: optimized }
  ]
}

const buildAlertSeries = (threshold = 100000) => {
  const base = Number(threshold) || 100000
  return Array.from({ length: 12 }, (_, index) => {
    const value = Math.round(base * (0.82 + index * 0.025 + Math.sin(index / 1.2) * 0.16))
    return { name: `第${index + 1}期`, value }
  })
}

const normalizeFieldKey = (value) => String(value || '').trim().toLowerCase()

const fieldKeyCandidates = (...values) => {
  const result = []
  const push = (value) => {
    const text = String(value ?? '').trim()
    if (!text) return
    result.push(text)
    const displayName = text.replace(/[（(].*$/, '').trim()
    if (displayName && displayName !== text) result.push(displayName)
    const columnMatch = text.match(/\bcol[_-]?\d+\b/i)
    if (columnMatch) result.push(columnMatch[0])
    const wrappedMatches = [...text.matchAll(/[（(]\s*([^()（）]+?)\s*[)）]/g)]
    wrappedMatches.forEach(match => {
      const inner = String(match[1] || '').trim()
      if (inner) result.push(inner)
    })
  }
  values.forEach(push)
  return [...new Set(result.map(item => String(item || '').trim()).filter(Boolean))]
}

const alertPointMatches = (item = {}, operator = 'lt', threshold = 0) => {
  const value = Number(item?.value)
  if (!Number.isFinite(value)) return false
  if (operator === 'gt') return value > threshold
  if (operator === 'zscore') return Math.abs(Number(item?.zScore || 0)) >= 3
  return value < threshold
}

const buildAlertSeriesFromCurrentChart = ({ tableName = '', timeField = '', metricField = '', operator = 'lt', threshold = 0 } = {}) => {
  const analysis = lastAnalysis?.value
  if (!analysis) return []
  const analysisTable = String(analysis.tableName || '').trim()
  if (tableName && analysisTable && tableName !== analysisTable) return []
  const rows = resolveLastAnalysisTimeSeries({ timeField, metricField })
  if (rows.length < 3) return []
  return rows.slice(-24).map(item => ({
    name: item.name,
    bucketName: item.name,
    value: item.value,
    triggered: alertPointMatches(item, operator, Number(threshold))
  }))
}

const normalizeAlertPreviewSeries = (rows = [], operator = 'lt', threshold = 0) => {
  if (!Array.isArray(rows)) return []
  return rows.map(item => {
    const name = String(item?.name || item?.bucketName || item?.bucket_name || item?.date || '').trim()
    const value = parseChartNumber(item?.value ?? item?.history ?? item?.metric_value ?? item?.metricValue ?? item?.actualValue)
    return {
      name,
      bucketName: name,
      value,
      triggered: alertPointMatches({ value, zScore: item?.zScore }, operator, Number(threshold))
    }
  }).filter(item => item.name && item.value != null)
}

const fetchAlertRulePreviewSeries = async (rule = {}, params = {}, sourceQuestion = '', signal) => {
  const tableName = String(rule?.tableName || params.tableName || selectedTableName?.value || '').trim()
  const timeField = String(rule?.timeField || params.timeField || '').trim()
  const metricField = String(rule?.metricField || params.metricField || '').trim()
  if (!tableName || !timeField || !metricField) return []
  const operator = String(rule?.operator || params.operator || 'lt').toLowerCase()
  const threshold = Number(rule?.threshold ?? params.threshold ?? 0)
  const result = await runAdvancedForecast({
    tableName,
    timeField,
    metricField,
    filterExpression: rule?.filterExpression || params.filterExpression || '',
    granularity: rule?.granularity || params.granularity || 'day',
    horizon: 1,
    algorithm: 'Holt-Winters',
    sourceQuestion
  }, signal ? { signal } : undefined)
  return normalizeAlertPreviewSeries(
    Array.isArray(result?.series) ? result.series.filter(item => item?.history != null) : [],
    operator,
    threshold
  )
}

const normalizeAdvancedIntentType = (type) => {
  const value = String(type || '').trim()
  if (['forecast', 'timeSeriesForecast', 'prediction'].includes(value)) return 'forecast'
  if (['whatIf', 'simulation', 'scenario'].includes(value)) return 'whatIf'
  if (['alert', 'warning', 'anomaly'].includes(value)) return 'alert'
  return ''
}

const normalizeLlmVariables = (items) => {
  if (!Array.isArray(items)) return []
  return items.map(item => ({
    name: String(item?.name || item?.variable || item?.label || '').trim(),
    field: String(item?.field || item?.columnName || '').trim(),
    change: Number(item?.change ?? item?.changePercent ?? item?.delta ?? 0)
  })).filter(item => item.name && Number.isFinite(item.change))
}

const normalizeHorizonCount = (horizon, granularity = 'month', sourceText = '') => {
  const normalizedGranularity = normalizeForecastGranularity(granularity)
  if (typeof horizon === 'number' && Number.isFinite(horizon) && horizon > 0) {
    return Math.max(1, Math.min(Math.round(horizon), 60))
  }
  const value = String(horizon ?? '').trim()
  if (/^\d+$/.test(value)) {
    return Math.max(1, Math.min(Number(value), 60))
  }
  const legacy = value.match(/^(\d+)\s*([dwmyq])$/i)
  if (legacy) {
    const unitMap = { d: 'day', w: 'week', m: 'month', q: 'quarter', y: 'year' }
    return Math.max(1, Math.min(horizonByGranularity({
      value: Number(legacy[1]),
      unit: unitMap[legacy[2].toLowerCase()] || 'month'
    }, normalizedGranularity), 60))
  }
  const range = explicitForecastRange(value) || explicitForecastRange(sourceText)
  if (range) {
    return Math.max(1, Math.min(horizonByGranularity(range, normalizedGranularity), 60))
  }
  return Math.max(1, Math.min(defaultForecastHorizon(normalizedGranularity), 60))
}

const parseChartNumber = (value) => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const text = String(value ?? '').replace(/,/g, '').trim()
  if (!text) return null
  const parsed = Number(text)
  return Number.isFinite(parsed) ? parsed : null
}

const hasExplicitPreviousResultReference = (text = '') => /刚才|上一轮|上一次|这个图|这张图|当前图|这个走势|基于这个|基于刚才|按刚才|按这个|用刚才|用这个/.test(String(text || ''))

const hasExplicitForecastText = (text = '') => /预测|预估|推算|未来|后面|下个月|下季度|下一季度|后续|大概会到|会到多少|继续涨|继续跌|往后推|趋势延伸|forecast|prediction/i.test(String(text || ''))

const looksLikeTemporalName = (value) => {
  const text = String(value || '').trim()
  return /^\d{4}([-/.年]\d{1,2})?([-/.月]\d{1,2})?.*/.test(text) ||
    /^\d{4}-Q[1-4]$/i.test(text) ||
    /^\d{4}-W\d{1,2}$/i.test(text)
}

const validateQueryResultForecastSeries = (series = []) => {
  const rows = Array.isArray(series) ? series : []
  if (rows.length < 3) {
    return { ok: false, reason: '上一轮查询结果不足 3 个有效时间点，建议改用原始数据源重新预测。' }
  }
  const temporalCount = rows.filter(item => looksLikeTemporalName(item?.name)).length
  if (temporalCount < Math.max(3, Math.ceil(rows.length * 0.6))) {
    return { ok: false, reason: '上一轮查询结果不是稳定的时间序列，建议改用原始数据源重新预测。' }
  }
  const numericRows = rows.filter(item => Number.isFinite(Number(item?.value)))
  if (numericRows.length < 3) {
    return { ok: false, reason: '上一轮查询结果未包含有效数值，建议改用原始数据源重新预测。' }
  }
  const hasNonZero = numericRows.some(item => Math.abs(Number(item.value)) > 0.000001)
  if (!hasNonZero) {
    return { ok: false, reason: '上一轮查询结果未包含有效数值，建议改用原始数据源重新预测。' }
  }
  return { ok: true, reason: '' }
}

const getRowValueByCandidates = (row, candidates = []) => {
  if (!row || typeof row !== 'object') return undefined
  for (const key of candidates) {
    if (key && Object.prototype.hasOwnProperty.call(row, key)) {
      return row[key]
    }
  }
  const normalizedCandidates = candidates.map(normalizeFieldKey).filter(Boolean)
  for (const [key, value] of Object.entries(row)) {
    const normalizedKey = normalizeFieldKey(key)
    if (normalizedCandidates.some(candidate => normalizedKey === candidate || normalizedKey.includes(candidate) || candidate.includes(normalizedKey))) {
      return value
    }
  }
  return undefined
}

const resolveLastAnalysisTimeSeries = ({ timeField = '', metricField = '' } = {}) => {
  const analysis = lastAnalysis?.value
  const rows = Array.isArray(analysis?.data) ? analysis.data : []
  if (rows.length < 3) return []
  const mapping = analysis?.fieldMapping || {}
  const dimensionKey = String(mapping.dimensionKey || '').trim()
  const metricKey = String(mapping.metricKey || '').trim()
  const dimensionCandidates = fieldKeyCandidates(
    timeField,
    dimensionKey,
    mapping.dimension,
    mapping.dimensionField,
    mapping.xField,
    'name',
    'dim_name',
    'dimension',
    'bucket_name',
    'date',
    'month',
    'time'
  )
  const metricCandidates = fieldKeyCandidates(
    metricField,
    metricKey,
    mapping.metric,
    mapping.metricField,
    mapping.yField,
    'value',
    'metric_value',
    'metric',
    'sales_amt',
    'amount',
    'total'
  )
  return rows.map((row, index) => {
    const name = String(getRowValueByCandidates(row, dimensionCandidates) ?? '').trim()
    const value = parseChartNumber(getRowValueByCandidates(row, metricCandidates))
    return {
      name: name || `历史${index + 1}`,
      value
    }
  }).filter(item => item.name && item.value != null)
}

const nextSeriesName = (lastName, offset) => {
  const text = String(lastName || '').trim()
  const monthMatch = text.match(/^(\d{4})-(\d{1,2})$/)
  if (monthMatch) {
    const date = new Date(Number(monthMatch[1]), Number(monthMatch[2]) - 1 + offset, 1)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
  }
  const dayMatch = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/)
  if (dayMatch) {
    const date = new Date(Number(dayMatch[1]), Number(dayMatch[2]) - 1, Number(dayMatch[3]) + offset)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  }
  return `未来${offset}`
}

const buildForecastSeriesFromChartData = (params = {}) => {
  const historyRows = resolveLastAnalysisTimeSeries()
  if (historyRows.length < 3) return []
  const futureCount = normalizeHorizonCount(params.horizon, params.granularity || 'month', params.sourceQuestion || '')
  const first = historyRows[0].value
  const last = historyRows[historyRows.length - 1].value
  const trend = historyRows.length > 1 ? (last - first) / (historyRows.length - 1) : 0
  const average = historyRows.reduce((sum, item) => sum + item.value, 0) / historyRows.length
  const std = Math.sqrt(historyRows.reduce((sum, item) => sum + Math.pow(item.value - average, 2), 0) / historyRows.length)
  const alpha = 0.55
  let level = first
  historyRows.forEach(item => {
    level = alpha * item.value + (1 - alpha) * level
  })
  const rows = historyRows.map(item => ({
    name: item.name,
    history: item.value,
    forecast: null,
    upper: null,
    lower: null
  }))
  for (let index = 1; index <= futureCount; index += 1) {
    const forecast = Math.max(0, Math.round(level + trend * index))
    const band = Math.max(Math.abs(forecast) * 0.12, std * 1.2)
    rows.push({
      name: nextSeriesName(historyRows[historyRows.length - 1].name, index),
      history: null,
      forecast,
      upper: Math.round(forecast + band),
      lower: Math.max(0, Math.round(forecast - band))
    })
  }
  return rows
}

const normalizeFieldText = (value) => String(value || '')
  .trim()
  .toLowerCase()
  .replace(/[`"'“”‘’（）()\[\]{}<>《》\s_-]+/g, '')

const fieldSearchValues = (field) => [
  field?.columnName,
  field?.displayName,
  field?.sourceFieldName,
  field?.fieldComment,
  field?.businessName,
  field?.synonyms
].map(value => String(value || '').trim()).filter(Boolean)

const fieldSemanticGroups = [
  ['\u9500\u552e\u989d', '\u9500\u552e\u91d1\u989d', '\u9500\u552e\u6536\u5165', '\u9500\u552e', '\u8425\u6536', '\u6536\u5165', 'salesamt', 'saleamt', 'salesamount', 'sales', 'revenue', 'amount', 'amt', 'gmv'],
  ['\u5229\u6da6', '\u6bdb\u5229', '\u51c0\u5229', 'profit', 'margin'],
  ['\u6210\u672c', '\u8d39\u7528', '\u652f\u51fa', 'cost', 'expense'],
  ['\u9500\u91cf', '\u6570\u91cf', '\u4ef6\u6570', '\u8ba2\u5355\u91cf', 'quantity', 'qty', 'volume', 'count', 'orders'],
  ['\u5355\u4ef7', '\u5ba2\u5355\u4ef7', '\u4ef7\u683c', 'price', 'unitprice', 'arpu'],
  ['\u65e5\u671f', '\u65f6\u95f4', '\u4e0b\u5355\u65f6\u95f4', '\u8ba2\u5355\u65f6\u95f4', 'date', 'time', 'day', 'month', 'orderdate', 'createdat', 'createtime'],
  ['\u533a\u57df', '\u5730\u533a', '\u5927\u533a', '\u57ce\u5e02', '\u7701\u4efd', 'region', 'area', 'city', 'province'],
  ['\u6e20\u9053', '\u6765\u6e90', 'channel', 'source'],
  ['\u54c1\u7c7b', '\u7c7b\u522b', '\u5206\u7c7b', '\u7c7b\u578b', 'category', 'type', 'class']
]

const scoreFieldMatch = (field, target = '') => {
  const preferred = normalizeFieldText(target)
  if (!preferred) return 0
  const values = fieldSearchValues(field)
  const normalizedValues = values.map(normalizeFieldText).filter(Boolean)
  let score = 0
  normalizedValues.forEach(value => {
    if (value === preferred) score = Math.max(score, 100)
    else if (value.includes(preferred) || preferred.includes(value)) score = Math.max(score, 80)
  })
  const joinedFieldText = normalizedValues.join(' ')
  fieldSemanticGroups.forEach(group => {
    const normalizedGroup = group.map(normalizeFieldText)
    const targetHit = normalizedGroup.some(term => term && (preferred.includes(term) || term.includes(preferred)))
    if (!targetHit) return
    const fieldHit = normalizedGroup.some(term => term && joinedFieldText.includes(term))
    if (fieldHit) score = Math.max(score, 70)
  })
  return score
}

const formulaFieldAliasValues = (field) => {
  const values = fieldSearchValues(field)
  const normalizedValues = values.map(normalizeFieldText).filter(Boolean)
  const joinedFieldText = normalizedValues.join(' ')
  const semanticValues = []
  fieldSemanticGroups.forEach(group => {
    const normalizedGroup = group.map(normalizeFieldText)
    const fieldHit = normalizedGroup.some(term => term && joinedFieldText.includes(term))
    if (fieldHit) semanticValues.push(...group)
  })
  return [...new Set([...values, ...semanticValues].map(value => String(value || '').trim()).filter(Boolean))]
}

const fieldNameMatches = (field, target) => scoreFieldMatch(field, target) > 0

const pickFieldName = (fields = [], preferred = '', fallback = '') => {
  const scored = fields
    .map((field, index) => ({ field, index, score: scoreFieldMatch(field, preferred) }))
    .filter(item => item.score > 0)
    .sort((a, b) => b.score - a.score || a.index - b.index)
  const matched = scored[0]?.field
  return String(matched?.columnName || fallback || fields[0]?.columnName || '').trim()
}

const pickFieldNameStrict = (fields = [], preferred = '', fallback = '') => {
  const scored = fields
    .map((field, index) => ({ field, index, score: scoreFieldMatch(field, preferred) }))
    .filter(item => item.score > 0)
    .sort((a, b) => b.score - a.score || a.index - b.index)
  return String(scored[0]?.field?.columnName || fallback || '').trim()
}

const businessSemanticMetricField = (intent = {}) => {
  const trace = intent?.businessSemanticTrace && typeof intent.businessSemanticTrace === 'object'
    ? intent.businessSemanticTrace
    : {}
  return String(
    intent?.metricField ||
    intent?.targetMetricField ||
    trace.analysisMetricField ||
    trace.metricColumn ||
    trace.resolvedMetricField ||
    ''
  ).trim()
}

const whatIfFormulaStopWords = [
  '推演',
  '预测',
  '变化',
  '会怎么',
  '会怎样',
  '怎么办',
  '结果',
  '分析',
  '测算',
  '模拟',
  '时',
  '如果',
  '若',
  '假设',
  '并',
  '请'
]

const normalizeWhatIfFormulaSyntax = (value = '') => String(value || '')
  .replace(/＝/g, '=')
  .replace(/[（]/g, '(')
  .replace(/[）]/g, ')')
  .replace(/，/g, ',')
  .replace(/\bSAFE[\s-]+DIVIDE\b/gi, 'SAFE_DIVIDE')

const unsafeWhatIfFormulaPattern = /\b(select|from|where|drop|delete|update|insert|alter|union|sleep|benchmark)\b/i

const hasUnsafeWhatIfFormulaText = (value = '') => {
  const text = normalizeWhatIfFormulaSyntax(value).replace(/\s+/g, ' ').trim()
  if (!text || !unsafeWhatIfFormulaPattern.test(text)) return false
  return /(?:公式|按|按照)/.test(text) || /=/.test(text)
}

const trimWhatIfFormulaTail = (value = '') => {
  let formula = normalizeWhatIfFormulaSyntax(value)
    .replace(/[。；;、]/g, ' ')
    .replace(/\s*,\s*/g, ', ')
    .replace(/\s+/g, ' ')
    .trim()
  for (const word of whatIfFormulaStopWords) {
    const index = formula.indexOf(word)
    if (index > 0) {
      formula = formula.slice(0, index).trim()
    }
  }
  formula = formula
    .replace(/\s*(提升|增长|上涨|下降|降低|减少)\s*$/g, '')
    .replace(/[\s,，。；;、]+$/g, '')
    .trim()
  return formula
}

const hasWhatIfFormulaExpression = (text = '', params = {}, llmIntent = {}) => {
  const explicit = String(params.formula || '').trim()
  if (explicit) return true
  const content = String(text || '').trim()
  return Boolean(
    inferWhatIfFormula(content, llmIntent)
    || hasExplicitWhatIfFormulaIntent(content)
  )
}

const assertWhatIfFormulaAllowed = (formula = '') => {
  const expression = normalizeWhatIfFormulaSyntax(formula).trim()
  if (!expression) return
  if (unsafeWhatIfFormulaPattern.test(expression)) {
    throw new Error('业务公式仅支持字段、数字、函数、条件和安全运算符，不能包含 SQL 语句')
  }
}

const assertWhatIfInstructionAllowed = (text = '', params = {}, llmIntent = {}) => {
  const explicit = String(params.formula || '').trim()
  if (hasUnsafeWhatIfFormulaText(text) || hasUnsafeWhatIfFormulaText(explicit)) {
    throw new Error('业务公式仅支持字段、数字、函数、条件和安全运算符，不能包含 SQL 语句')
  }
  assertWhatIfFormulaAllowed(explicit || inferWhatIfFormula(text, llmIntent))
}

const stripWhatIfFormulaDisplayName = (formula = '') => {
  const expression = String(formula || '').trim()
  let depth = 0
  for (let index = 0; index < expression.length; index += 1) {
    const ch = expression[index]
    if (ch === '(') {
      depth += 1
      continue
    }
    if (ch === ')') {
      depth = Math.max(0, depth - 1)
      continue
    }
    if (depth !== 0 || ch !== '=') continue
    const previous = index > 0 ? expression[index - 1] : ''
    const next = index + 1 < expression.length ? expression[index + 1] : ''
    if (['>', '<', '!', '='].includes(previous) || next === '=') continue
    return expression.slice(index + 1).trim()
  }
  return expression
}

const rewriteWhatIfFormulaToColumns = (formula = '', fields = []) => {
  let expression = stripWhatIfFormulaDisplayName(trimWhatIfFormulaTail(formula))
  if (!expression) return ''
  const aliasEntries = []
  fields.forEach(field => {
    const column = String(field?.columnName || '').trim()
    if (!column) return
    formulaFieldAliasValues(field).forEach(alias => {
      const text = String(alias || '').trim()
      if (!text || text === column) return
      aliasEntries.push({ text, column })
    })
  })
  aliasEntries
    .sort((a, b) => b.text.length - a.text.length)
    .forEach(({ text, column }) => {
      const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      expression = expression.replace(new RegExp(escaped, 'gi'), column)
    })
  return trimWhatIfFormulaTail(expression)
}

const extractWhatIfFormulaFields = (formula = '', fields = []) => {
  const expression = rewriteWhatIfFormulaToColumns(formula, fields)
  if (!expression) return []
  const tokens = [...expression.matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)].map(match => match[0])
  const functionNames = new Set(['IF', 'ABS', 'MIN', 'MAX', 'ROUND', 'DIVIDE', 'SAFE_DIVIDE'])
  const columns = new Set((fields || []).map(field => String(field?.columnName || '').trim()).filter(Boolean))
  return [...new Set(tokens.filter(token => columns.has(token) && !functionNames.has(token.toUpperCase())))]
}

const completeWhatIfVariablesFromFormula = (variables = [], formula = '', fields = []) => {
  const rows = Array.isArray(variables) ? variables.map(item => ({ ...item })) : []
  const existing = new Set(rows.map(item => String(item.field || '').trim()).filter(Boolean))
  extractWhatIfFormulaFields(formula, fields).forEach(column => {
    if (existing.has(column)) return
    const fieldMeta = fields.find(field => String(field.columnName || '') === column)
    rows.push({
      field: column,
      name: fieldMeta?.displayName || fieldMeta?.sourceFieldName || column,
      mode: 'percent',
      change: 0,
      min: null,
      max: null,
      formulaOnly: true
    })
    existing.add(column)
  })
  return rows
}

const makeAbortableConfirmResolver = (resolve, signal, onAbort) => {
  if (signal?.aborted) {
    onAbort?.()
    resolve(null)
    return null
  }
  let settled = false
  let abortHandler = null
  const finish = (value) => {
    if (settled) return
    settled = true
    if (signal && abortHandler) {
      signal.removeEventListener('abort', abortHandler)
    }
    resolve(value)
  }
  abortHandler = () => {
    onAbort?.()
    finish(null)
  }
  signal?.addEventListener('abort', abortHandler, { once: true })
  return finish
}

const confirmForecastParams = (fieldMeta, defaults = {}, signal) => new Promise((resolve) => {
  const finish = makeAbortableConfirmResolver(resolve, signal, () => {
    forecastConfirmVisible.value = false
    forecastConfirmResolver = null
  })
  if (!finish) return
  forecastConfirmMeta.value = {
    timeFields: Array.isArray(fieldMeta?.timeFields) ? fieldMeta.timeFields : [],
    numericFields: Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
  }
  forecastConfirmForm.value = {
    tableName: defaults.tableName || '',
    timeField: defaults.timeField || forecastConfirmMeta.value.timeFields[0]?.columnName || '',
    metricField: defaults.metricField || forecastConfirmMeta.value.numericFields[0]?.columnName || '',
    filterExpression: defaults.filterExpression || '',
    granularity: defaults.granularity || 'month',
    horizon: defaults.horizon || 3,
    algorithm: defaults.algorithm || 'Holt-Winters',
    alpha: defaults.alpha ?? 0.55,
    beta: defaults.beta ?? 0.28,
    gamma: defaults.gamma ?? 0.20,
    seasonLength: defaults.seasonLength ?? 0
  }
  forecastConfirmResolver = finish
  forecastConfirmVisible.value = true
})

const submitForecastConfirm = () => {
  if (!forecastConfirmForm.value.timeField || !forecastConfirmForm.value.metricField) {
    ElMessage.warning('请选择时间字段和指标字段')
    return
  }
  forecastConfirmVisible.value = false
  if (forecastConfirmResolver) {
    forecastConfirmResolver({ ...forecastConfirmForm.value })
    forecastConfirmResolver = null
  }
}

const cancelForecastConfirm = () => {
  forecastConfirmVisible.value = false
  if (forecastConfirmResolver) {
    forecastConfirmResolver(null)
    forecastConfirmResolver = null
  }
}

const confirmWhatIfParams = (fieldMeta, defaults = {}, signal) => new Promise((resolve) => {
  const finish = makeAbortableConfirmResolver(resolve, signal, () => {
    whatIfConfirmVisible.value = false
    whatIfConfirmResolver = null
  })
  if (!finish) return
  const numericFields = Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
  whatIfConfirmMeta.value = { numericFields }
  whatIfConfirmForm.value = {
    targetMetric: defaults.targetMetric || numericFields[0]?.columnName || '',
    formula: defaults.formula || '',
    formulaScope: defaults.formulaScope || 'aggregate',
    variables: (defaults.variables?.length ? defaults.variables : [{ field: numericFields[1]?.columnName || numericFields[0]?.columnName || '', name: '变量', change: 10, mode: 'percent' }])
      .map(item => ({
        field: item.field || pickFieldNameStrict(numericFields, item.name, ''),
        name: item.name || formatAdvancedFieldLabel(numericFields.find(field => field.columnName === item.field)) || item.field || '变量',
        mode: item.mode || 'percent',
        change: Number(item.change ?? 0),
        min: item.min ?? null,
        max: item.max ?? null
      }))
  }
  whatIfConfirmResolver = finish
  whatIfConfirmVisible.value = true
})

const addWhatIfConfirmVariable = () => {
  const firstField = whatIfConfirmMeta.value.numericFields?.[0]?.columnName || ''
  whatIfConfirmForm.value.variables.push({ field: firstField, name: '变量', mode: 'percent', change: 10, min: null, max: null })
}

const removeWhatIfConfirmVariable = (index) => {
  whatIfConfirmForm.value.variables.splice(index, 1)
}

const submitWhatIfConfirm = () => {
  if (!whatIfConfirmForm.value.targetMetric) {
    ElMessage.warning('请选择目标指标')
    return
  }
  const formula = rewriteWhatIfFormulaToColumns(
    whatIfConfirmForm.value.formula || '',
    whatIfConfirmMeta.value.numericFields || []
  )
  try {
    assertWhatIfFormulaAllowed(formula || whatIfConfirmForm.value.formula || '')
  } catch (error) {
    ElMessage.error(error.message || '业务公式不合法')
    return
  }
  const variables = completeWhatIfVariablesFromFormula(
    whatIfConfirmForm.value.variables,
    formula,
    whatIfConfirmMeta.value.numericFields || []
  )
    .filter(item => item.field && Number.isFinite(Number(item.change)))
    .map(item => ({
      ...item,
      mode: item.mode || 'percent',
      change: Number(item.change),
      min: item.min === null || item.min === '' ? null : Number(item.min),
      max: item.max === null || item.max === '' ? null : Number(item.max)
    }))
  if (!variables.length) {
    ElMessage.warning('请至少配置一个有效变量')
    return
  }
  whatIfConfirmVisible.value = false
  if (whatIfConfirmResolver) {
    whatIfConfirmResolver({
      targetMetric: whatIfConfirmForm.value.targetMetric,
      formula,
      formulaScope: formula ? whatIfConfirmForm.value.formulaScope || 'aggregate' : 'aggregate',
      variables
    })
    whatIfConfirmResolver = null
  }
}

const cancelWhatIfConfirm = () => {
  whatIfConfirmVisible.value = false
  if (whatIfConfirmResolver) {
    whatIfConfirmResolver(null)
    whatIfConfirmResolver = null
  }
}

const confirmAlertParams = (fieldMeta, defaults = {}, signal) => new Promise((resolve) => {
  const finish = makeAbortableConfirmResolver(resolve, signal, () => {
    alertConfirmVisible.value = false
    alertConfirmResolver = null
  })
  if (!finish) return
  alertConfirmMeta.value = {
    timeFields: Array.isArray(fieldMeta?.timeFields) ? fieldMeta.timeFields : [],
    numericFields: Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
  }
  const channel = String(defaults.channel || '').trim()
  alertConfirmForm.value = {
    tableName: defaults.tableName || '',
    timeField: defaults.timeField || alertConfirmMeta.value.timeFields[0]?.columnName || '',
    metricField: defaults.metricField || alertConfirmMeta.value.numericFields[0]?.columnName || '',
    filterExpression: defaults.filterExpression || '',
    granularity: defaults.granularity || 'day',
    operator: defaults.operator || 'lt',
    threshold: Number(defaults.threshold ?? 100000),
    detectionCycle: defaults.detectionCycle || 'daily',
    channels: Array.isArray(defaults.channels) && defaults.channels.length
      ? defaults.channels
      : channel === 'email'
        ? ['email']
        : channel === 'dingtalk'
          ? ['dingtalk']
          : ['email', 'dingtalk']
  }
  alertConfirmResolver = finish
  alertConfirmVisible.value = true
})

const submitAlertConfirm = () => {
  if (!alertConfirmForm.value.timeField || !alertConfirmForm.value.metricField) {
    ElMessage.warning('请选择时间字段和指标字段')
    return
  }
  if (alertConfirmForm.value.operator !== 'zscore' && !Number.isFinite(Number(alertConfirmForm.value.threshold))) {
    ElMessage.warning('请填写有效阈值')
    return
  }
  if (!alertConfirmForm.value.channels.length) {
    ElMessage.warning('请至少选择一个通知渠道')
    return
  }
  alertConfirmVisible.value = false
  if (alertConfirmResolver) {
    alertConfirmResolver({ ...alertConfirmForm.value })
    alertConfirmResolver = null
  }
}

const cancelAlertConfirm = () => {
  alertConfirmVisible.value = false
  if (alertConfirmResolver) {
    alertConfirmResolver(null)
    alertConfirmResolver = null
  }
}

const confirmChatAlertDraft = async (draft = {}, msg = {}) => {
  const tableName = String(draft.tableName || msg.sourceTableName || selectedTableName?.value || '').trim()
  if (!tableName) {
    ElMessage.warning('缺少预警规则所属数据源，无法创建规则')
    return
  }
  const draftKey = alertDraftKey(draft)
  savingAlertDraftKey.value = draftKey
  try {
    const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName })
    const confirmedAlert = await confirmAlertParams(fieldMeta, {
      tableName,
      timeField: pickFieldName(fieldMeta?.timeFields || [], draft.timeField, ''),
      metricField: pickFieldName(fieldMeta?.numericFields || [], draft.metricField, ''),
      filterExpression: draft.filterExpression || '',
      granularity: draft.granularity || 'month',
      operator: draft.operator || 'lt',
      threshold: draft.threshold,
      detectionCycle: draft.detectionCycle || 'daily',
      channels: Array.isArray(draft.channels) ? draft.channels : [],
      channel: Array.isArray(draft.channels) ? '' : draft.channel || 'both'
    })
    if (!confirmedAlert) {
      return
    }
    const savedRule = await saveAdvancedAlertRule({
      ...confirmedAlert,
      sourceQuestion: draft.sourceQuestion || msg.sourceQuestion || ''
    })
    let previewSeries = []
    try {
      previewSeries = await fetchAlertRulePreviewSeries(savedRule, confirmedAlert, draft.sourceQuestion || msg.sourceQuestion || '')
    } catch (previewError) {
      console.warn('load alert preview series failed:', previewError)
      ElMessage.warning('预警规则已创建，但真实检测曲线预览加载失败，将尝试使用当前图表数据')
    }
    const analysis = buildAnalysisFromSavedAlertRule(
      savedRule,
      draft.sourceQuestion || msg.sourceQuestion || '预警规则',
      confirmedAlert,
      { metricField: confirmedAlert.metricField },
      fieldMeta,
      { previewSeries, previewSource: previewSeries.length ? 'backend-series' : '' }
    )
    const record = withAdvancedChartRecommendation(analysis)
    advancedAnalysisHistory.value = [record, ...advancedAnalysisHistory.value.filter(item => item.id !== record.id)].slice(0, 20)
    activeAdvancedAnalysis.value = record
    replaceAlertDraftMessageWithCreatedRule(msg, record, savedRule)
    try {
      const persisted = await persistAlertDraftCreatedState(msg, record, savedRule)
      if (persisted?.artifactId && msg) {
        msg.artifactId = String(persisted.artifactId)
      }
    } catch (persistError) {
      console.warn('persist alert draft created state failed:', persistError)
      ElMessage.warning('预警规则已创建，但会话卡片状态保存失败，刷新后可能需要重新进入预警规则管理查看')
    }
    advancedAnalysisDialogVisible.value = true
    await loadAdvancedAlertRules()
    ElMessage.success('预警规则已创建')
  } catch (error) {
    ElMessage.error(`创建预警规则失败：${error.message || '未知原因'}`)
  } finally {
    if (savingAlertDraftKey.value === draftKey) {
      savingAlertDraftKey.value = ''
    }
  }
}

const buildAnalysisFromRealForecast = (result, text, params, llmIntent, fieldMeta = {}) => {
  const metric = String(llmIntent?.metric || result?.metricField || '').trim() || inferMetricFromQuestion(text)
  const forecastRows = Array.isArray(result?.series) ? result.series.filter(item => item?.forecast != null) : []
  const historyRows = Array.isArray(result?.series) ? result.series.filter(item => item?.history != null) : []
  return {
    id: `advanced-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    type: 'forecast',
    title: `${metric}趋势预测`,
    summary: '已基于真实历史数据生成预测结果，预测值与置信区间由后端算法计算。',
    tableName: result?.tableName || selectedTableName?.value || '',
    metric: formatAnalysisMetricLabel(result?.metricField || llmIntent?.metricField || metric, metric, fieldMeta?.numericFields),
    timeRange: forecastTimeRangeLabel(params.horizon, result?.granularity || params.granularity || 'month'),
    status: '真实计算',
    chartRecommendation: result?.chartRecommendation,
    chartRuleCode: result?.chartRuleCode,
    chartRuleName: result?.chartRuleName,
    chartScenarioType: result?.chartScenarioType,
    chartRecommendationStatus: result?.chartRecommendationStatus,
    chartRecommendationExplain: formatChartRecommendationExplain(result?.chartRecommendationExplain, {
      ruleCode: result?.chartRuleCode,
      ruleName: result?.chartRuleName,
      scenarioType: result?.chartScenarioType,
      status: result?.chartRecommendationStatus
    }),
    params: {
      horizon: params.horizon,
      algorithm: result?.algorithm || params.algorithm || 'Holt-Winters',
      confidence: result?.confidence || params.confidence || '95%',
      algorithmParams: result?.algorithmParams || {},
      alpha: result?.algorithmParams?.alpha ?? params.alpha,
      beta: result?.algorithmParams?.beta ?? params.beta,
      gamma: result?.algorithmParams?.gamma ?? params.gamma,
      seasonLength: result?.algorithmParams?.seasonLength ?? params.seasonLength,
      filterExpression: params.filterExpression || '',
      tableName: result?.tableName || params.tableName || selectedTableName?.value || '',
      timeField: result?.timeField || params.timeField || '',
      metricField: result?.metricField || params.metricField || '',
      granularity: result?.granularity || params.granularity || 'month',
      sourceSeries: Array.isArray(params.sourceSeries) ? params.sourceSeries : []
    },
    dataQuality: result?.dataQuality || null,
    explanation: result?.explanation || buildForecastCardExplanation({
      algorithm: result?.algorithm || params.algorithm,
      historyPoints: historyRows.length,
      forecastPoints: forecastRows.length,
      lastForecast: forecastRows[forecastRows.length - 1]?.forecast,
      source: params.sourceSeries?.length ? '上一轮查询结果' : '真实数据源'
    }),
    series: Array.isArray(result?.series) ? result.series : [],
    insights: Array.isArray(result?.insights)
      ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
      : []
  }
}

const buildAnalysisFromRealWhatIf = (result, text, params, llmIntent, fieldMeta = {}) => {
  const metric = String(llmIntent?.metric || result?.targetMetric || '').trim() || inferMetricFromQuestion(text)
  const series = Array.isArray(result?.series) ? result.series : []
  const base = series.find(item => item.name === '基准方案')?.value ?? series[0]?.value
  const scenario = series.find(item => item.name === '中性方案')?.value ?? series[1]?.value
  const recommended = series.find(item => item.name === '推荐方案')?.value ?? series[series.length - 1]?.value
  const formula = String(result?.formula || params.formula || '').trim()
  const formulaScope = formula ? String(result?.formulaScope || params.formulaScope || 'aggregate').trim() || 'aggregate' : 'aggregate'
  return {
    id: `advanced-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    type: 'whatIf',
    title: `${metric}情景推演`,
    summary: formula
      ? '已基于真实历史数据与业务公式计算情景结果，结果用于方案比较和口径验证。'
      : '已基于真实历史数据估计变量影响，结果用于情景比较和方案筛选。',
    tableName: result?.tableName || selectedTableName?.value || '',
    metric: formatAnalysisMetricLabel(result?.targetMetric || llmIntent?.targetMetricField || metric, metric, fieldMeta?.numericFields),
    timeRange: '当前分析周期',
    status: '真实计算',
    params: {
      tableName: result?.tableName || params.tableName || selectedTableName?.value || '',
      targetMetric: result?.targetMetric || params.targetMetric || '',
      formula,
      formulaScope,
      resolvedFormula: result?.resolvedFormula || '',
      calculationMode: result?.calculationMode || (formula ? 'formula' : 'regression'),
      variables: (Array.isArray(result?.variables) && result.variables.length ? result.variables : params.variables || []).map(item => ({ ...item }))
    },
    explanation: result?.explanation || buildWhatIfCardExplanation({
      base,
      scenario,
      recommended,
      variables: Array.isArray(result?.variables) ? result.variables : params.variables,
      formula
    }),
    series,
    insights: Array.isArray(result?.insights)
      ? result.insights.map(item => ({ label: String(item.label || ''), value: String(item.value ?? '') }))
      : []
  }
}

const buildAnalysisFromSavedAlertRule = (rule, text, params, llmIntent, fieldMeta = {}, options = {}) => {
  const metric = formatAnalysisMetricLabel(rule?.metricField || params.metricField || llmIntent?.metric || inferMetricFromQuestion(text), inferMetricFromQuestion(text), fieldMeta?.numericFields)
  const threshold = Number(rule?.threshold ?? params.threshold ?? inferAlertThreshold(text))
  const operator = rule?.operator || params.operator || 'lt'
  const tableName = rule?.tableName || params.tableName || selectedTableName?.value || ''
  const timeField = rule?.timeField || params.timeField || ''
  const metricField = rule?.metricField || params.metricField || ''
  const backendSeries = normalizeAlertPreviewSeries(options.previewSeries || [], operator, threshold)
  const currentChartSeries = backendSeries.length ? [] : buildAlertSeriesFromCurrentChart({
    tableName,
    timeField,
    metricField,
    operator,
    threshold
  })
  const realSeries = backendSeries.length ? backendSeries : currentChartSeries
  const usingRealSeries = realSeries.length > 0
  const series = usingRealSeries ? realSeries : buildAlertSeries(Number.isFinite(threshold) ? threshold : inferAlertThreshold(text))
  const abnormalCount = series.filter(item => alertPointMatches(item, operator, threshold)).length
  return {
    id: `advanced-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    type: 'alert',
    title: `${metric}预警规则`,
    summary: '预警规则已保存，后续离线 Agent 可按检测周期轮询数据并生成预警事件。',
    tableName,
    metric,
    timeRange: alertCycleLabel(rule?.detectionCycle || params.detectionCycle),
    status: rule?.status === 'ACTIVE' ? '已启用' : '已保存',
    ruleId: rule?.id,
    params: {
      operator,
      threshold,
      granularity: rule?.granularity || params.granularity || 'day',
      detectionCycle: rule?.detectionCycle || params.detectionCycle || 'daily',
      channels: Array.isArray(rule?.channels) ? rule.channels : params.channels || [],
      channel: formatAlertChannel(Array.isArray(rule?.channels) ? rule.channels : params.channels || []),
      filterExpression: rule?.filterExpression || params.filterExpression || '',
      timeField,
      metricField,
      previewSource: backendSeries.length ? 'backend-series' : (usingRealSeries ? 'current-chart' : 'simulated')
    },
    explanation: buildAlertCardExplanation({
      operator,
      threshold,
      channels: Array.isArray(rule?.channels) ? rule.channels : params.channels || [],
      detectionCycle: rule?.detectionCycle || params.detectionCycle || 'daily'
    }),
    series,
    insights: [
      { label: '规则ID', value: rule?.id || '-' },
      { label: '阈值', value: operator === 'zscore' ? 'Z-Score >= 3' : formatAdvancedNumber(threshold) },
      { label: usingRealSeries ? '命中点' : '模拟异常', value: `${abnormalCount} 次` },
      { label: '通知渠道', value: formatAlertChannel(Array.isArray(rule?.channels) ? rule.channels : params.channels || []) }
    ]
  }
}

const createAdvancedAnalysisAsync = async (type, text, params = {}, llmIntent = {}, signal) => {
  const tableName = selectedTableName?.value || lastAnalysis?.value?.tableName || ''
  if (type === 'whatIf') {
    assertWhatIfInstructionAllowed(text, params, llmIntent)
  }
  if (!tableName) {
    return createAdvancedAnalysis(type, text, params, llmIntent)
  }
  try {
      const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName }, signal ? { signal } : undefined)
    if (type === 'forecast') {
      const granularity = normalizeForecastGranularity(llmIntent.granularity || params.granularity || inferForecastGranularity(text))
      const mergedParams = {
        granularity,
        horizon: normalizeHorizonCount(params.horizon || llmIntent.horizon || null, granularity, text),
        algorithm: params.algorithm || llmIntent.algorithm || 'Holt-Winters',
        confidence: params.confidence || llmIntent.confidence || '95%',
        alpha: params.alpha ?? 0.55,
        beta: params.beta ?? 0.28,
        gamma: params.gamma ?? 0.20,
        seasonLength: params.seasonLength ?? 0
      }
      const questionMetric = inferMetricFromQuestion(text)
      const timeField = String(llmIntent.timeField || lastAnalysis?.value?.fieldMapping?.dimensionKey || text || '').trim()
      const metricField = String(businessSemanticMetricField(llmIntent) || lastAnalysis?.value?.fieldMapping?.metricKey || llmIntent.metric || questionMetric || text || '').trim()
      const inferredPayload = {
        tableName,
        timeField: pickFieldName(fieldMeta?.timeFields || [], timeField, ''),
        metricField: pickFieldName(fieldMeta?.numericFields || [], metricField, ''),
        filterExpression: params.filterExpression || llmIntent.filterExpression || '',
        granularity: mergedParams.granularity,
        horizon: mergedParams.horizon,
        algorithm: mergedParams.algorithm,
        alpha: mergedParams.alpha,
        beta: mergedParams.beta,
        gamma: mergedParams.gamma,
        seasonLength: mergedParams.seasonLength
      }
      const confirmedPayload = await confirmForecastParams(fieldMeta, inferredPayload, signal)
      if (!confirmedPayload) {
        throw new Error('已取消预测参数确认')
      }
      const payload = {
        ...inferredPayload,
        ...confirmedPayload
      }
      if (!payload.timeField || !payload.metricField) {
        const chartSeries = resolveLastAnalysisTimeSeries()
        const seriesCheck = validateQueryResultForecastSeries(chartSeries)
        if (!hasExplicitForecastText(text) || !hasExplicitPreviousResultReference(text)) {
          throw new Error('缺少可用于真实预测的时间字段或数值指标，请先选择字段后再预测。')
        }
        if (!seriesCheck.ok) {
          throw new Error(seriesCheck.reason)
        }
        const metric = String(llmIntent.metric || lastAnalysis?.value?.fieldMapping?.metric || inferMetricFromQuestion(text) || '').trim()
        if (!metric) {
          throw new Error('上一轮查询结果缺少明确指标，建议改用原始数据源重新预测。')
        }
        const result = await runAdvancedForecastFromSeries({
          tableName,
          metric,
          series: chartSeries,
          horizon: mergedParams.horizon,
          algorithm: mergedParams.algorithm,
          alpha: mergedParams.alpha,
          beta: mergedParams.beta,
          gamma: mergedParams.gamma,
          seasonLength: mergedParams.seasonLength
        }, signal ? { signal } : undefined)
        return buildAnalysisFromRealForecast(result, text, { ...mergedParams, sourceSeries: chartSeries }, llmIntent, fieldMeta)
      }
      const result = await runAdvancedForecast(payload, signal ? { signal } : undefined)
      return buildAnalysisFromRealForecast(result, text, {
        ...mergedParams,
        tableName: payload.tableName,
        timeField: payload.timeField,
        metricField: payload.metricField,
        granularity: payload.granularity,
        filterExpression: payload.filterExpression || ''
      }, llmIntent, fieldMeta)
    }
    if (type === 'whatIf') {
      const variables = params.variables?.length
        ? params.variables
        : (normalizeLlmVariables(llmIntent.variables).length ? normalizeLlmVariables(llmIntent.variables) : inferWhatIfVariables(text))
      const targetMetric = pickFieldName(
        fieldMeta?.numericFields || [],
        params.targetMetric || llmIntent.targetMetricField || llmIntent.metric || lastAnalysis?.value?.fieldMapping?.metricKey || '',
        ''
      )
      const numericFields = fieldMeta?.numericFields || []
      const defaultVariables = variables.map(variable => {
        const field = pickFieldNameStrict(numericFields, variable.field || variable.name, '')
        return { ...variable, field }
      })
      const formula = rewriteWhatIfFormulaToColumns(
        params.formula || inferWhatIfFormula(text, llmIntent),
        numericFields
      )
      assertWhatIfFormulaAllowed(formula)
      const confirmedWhatIf = await confirmWhatIfParams(fieldMeta, {
        targetMetric,
        formula,
        formulaScope: params.formulaScope || 'aggregate',
        variables: completeWhatIfVariablesFromFormula(defaultVariables, formula, numericFields)
      }, signal)
      if (!confirmedWhatIf) {
        throw new Error('已取消推演参数确认')
      }
      const normalizedVariables = confirmedWhatIf.variables
      if (!confirmedWhatIf.targetMetric || !normalizedVariables.length) {
        throw new Error('缺少可用于真实推演的目标指标或变量字段')
      }
      const result = await runAdvancedWhatIf({
        tableName,
        targetMetric: confirmedWhatIf.targetMetric,
        formula: confirmedWhatIf.formula || '',
        formulaScope: confirmedWhatIf.formula ? confirmedWhatIf.formulaScope || 'aggregate' : 'aggregate',
        sourceQuestion: text,
        variables: normalizedVariables
      }, signal ? { signal } : undefined)
      return buildAnalysisFromRealWhatIf(result, text, {
        tableName,
        targetMetric: confirmedWhatIf.targetMetric,
        formula: confirmedWhatIf.formula || '',
        formulaScope: confirmedWhatIf.formula ? confirmedWhatIf.formulaScope || 'aggregate' : 'aggregate',
        variables: normalizedVariables
      }, llmIntent, fieldMeta)
    }
    if (type === 'alert') {
      const questionMetric = inferMetricFromQuestion(text)
      const timeField = String(llmIntent.timeField || lastAnalysis?.value?.fieldMapping?.dimensionKey || text || '').trim()
      const metricField = String(businessSemanticMetricField(llmIntent) || lastAnalysis?.value?.fieldMapping?.metricKey || llmIntent.metric || questionMetric || text || '').trim()
      const operator = params.operator || llmIntent.operator || (/高于|超过|大于/.test(text) ? 'gt' : /异常|z-?score/i.test(text) ? 'zscore' : 'lt')
      const confirmedAlert = await confirmAlertParams(fieldMeta, {
        tableName,
        timeField: pickFieldName(fieldMeta?.timeFields || [], timeField, ''),
        metricField: pickFieldName(fieldMeta?.numericFields || [], metricField, ''),
        filterExpression: params.filterExpression || llmIntent.filterExpression || '',
        granularity: llmIntent.granularity || params.granularity || 'day',
        operator,
        threshold: params.threshold ?? llmIntent.threshold ?? inferAlertThreshold(text),
        detectionCycle: params.detectionCycle || llmIntent.detectionCycle || 'daily',
        channel: params.channel || llmIntent.channel || 'both'
      }, signal)
      if (!confirmedAlert) {
        throw new Error('已取消预警规则确认')
      }
      const savedRule = await saveAdvancedAlertRule(confirmedAlert, signal ? { signal } : undefined)
      let previewSeries = []
      try {
        previewSeries = await fetchAlertRulePreviewSeries(savedRule, confirmedAlert, text, signal)
      } catch (previewError) {
        console.warn('load alert preview series failed:', previewError)
        ElMessage.warning('预警规则已保存，但真实检测曲线预览加载失败，将尝试使用当前图表数据')
      }
      return buildAnalysisFromSavedAlertRule(savedRule, text, confirmedAlert, llmIntent, fieldMeta, {
        previewSeries,
        previewSource: previewSeries.length ? 'backend-series' : ''
      })
    }
  } catch (error) {
    console.warn('advanced analysis real compute fallback:', error)
    if (type === 'whatIf' && hasWhatIfFormulaExpression(text, params, llmIntent)) {
      throw error
    }
    if (type === 'forecast') {
      return createAdvancedAnalysis(type, text, params, {
        ...llmIntent,
        simulated: true,
        fallbackReason: error.message || '真实预测接口不可用'
      })
    }
    ElMessage.warning(`真实计算暂不可用，已使用前端模拟结果：${error.message || '未知原因'}`)
  }
  return createAdvancedAnalysis(type, text, params, llmIntent)
}

const createAdvancedAnalysis = (type, text, params = {}, llmIntent = {}) => {
  const metric = String(llmIntent.metric || llmIntent.targetMetric || '').trim() || inferMetricFromQuestion(text)
  const tableName = String(selectedTableName?.value || lastAnalysis?.value?.tableName || '').trim()
  const id = `advanced-${Date.now()}-${Math.random().toString(16).slice(2)}`
  if (type === 'forecast') {
    const granularity = normalizeForecastGranularity(llmIntent.granularity || params.granularity || inferForecastGranularity(text))
    const mergedParams = {
      granularity,
      horizon: normalizeHorizonCount(params.horizon || llmIntent.horizon || null, granularity, text),
      algorithm: params.algorithm || llmIntent.algorithm || 'Prophet',
      confidence: params.confidence || llmIntent.confidence || '95%'
    }
    const series = buildForecastSeries(mergedParams)
    const forecastRows = series.filter(item => item.forecast != null)
    const lastForecast = forecastRows[forecastRows.length - 1]?.forecast || 0
    return {
      id,
      type,
      title: `${metric}趋势预测`,
      summary: llmIntent.simulated
        ? `未取得可用真实时间序列，已生成模拟预测卡片用于参数预览。原因：${llmIntent.fallbackReason || '字段不足或数据不可用'}`
        : `基于当前对话上下文生成${mergedParams.confidence}置信区间预测曲线，可调整周期和算法后重新计算。`,
      tableName,
      metric,
      timeRange: forecastTimeRangeLabel(mergedParams.horizon, mergedParams.granularity),
      status: llmIntent.simulated ? '模拟生成' : '已生成',
      params: mergedParams,
      explanation: buildForecastCardExplanation({
        algorithm: mergedParams.algorithm,
        historyPoints: series.filter(item => item.history != null).length,
        forecastPoints: forecastRows.length,
        lastForecast,
        source: llmIntent.simulated ? '模拟数据' : '当前对话上下文'
      }),
      series,
      insights: [
        { label: '末期预测', value: formatAdvancedNumber(lastForecast) },
        { label: '算法', value: mergedParams.algorithm },
        { label: '置信区间', value: mergedParams.confidence }
      ]
    }
  }
  if (type === 'whatIf') {
    const variables = params.variables?.length
      ? params.variables
      : (normalizeLlmVariables(llmIntent.variables).length ? normalizeLlmVariables(llmIntent.variables) : inferWhatIfVariables(text))
    const series = buildWhatIfSeries(variables)
    const base = series[0]?.value || 0
    const scenario = series.find(item => item.name === '中性方案')?.value || series[1]?.value || 0
    const recommended = series.find(item => item.name === '推荐方案')?.value || series[series.length - 1]?.value || 0
    const delta = base ? ((scenario - base) / base) * 100 : 0
    const formula = params.formula || inferWhatIfFormula(text, llmIntent)
    const formulaScope = formula ? params.formulaScope || 'aggregate' : 'aggregate'
    return {
      id,
      type,
      title: `${metric}情景推演`,
      summary: formula
        ? '已根据变量变化和业务公式生成基准、保守、中性、乐观和推荐方案。'
        : '已根据变量变化生成基准、保守、中性、乐观和推荐方案，可继续调整变量重新计算。',
      tableName,
      metric,
      timeRange: '当前分析周期',
      status: '已生成',
      params: { variables, formula, formulaScope },
      explanation: buildWhatIfCardExplanation({ base, scenario, recommended, variables, formula }),
      series,
      insights: [
        { label: '模拟变化', value: `${delta >= 0 ? '+' : ''}${delta.toFixed(1)}%` },
        { label: '推荐方案', value: formatAdvancedNumber(recommended) },
        { label: '场景数', value: '3 个' },
        { label: '变量数', value: `${variables.length} 个` }
      ]
    }
  }
  const threshold = params.threshold ?? llmIntent.threshold ?? inferAlertThreshold(text)
  const operator = params.operator || llmIntent.operator || (/高于|超过|大于/.test(text) ? 'gt' : /异常|z-?score/i.test(text) ? 'zscore' : 'lt')
  const realSeries = buildAlertSeriesFromCurrentChart({
    tableName,
    timeField: params.timeField || llmIntent.timeField || '',
    metricField: params.metricField || llmIntent.metricField || llmIntent.metric || '',
    operator,
    threshold
  })
  const usingRealSeries = realSeries.length > 0
  const series = usingRealSeries ? realSeries : buildAlertSeries(threshold)
  const abnormalCount = series.filter(item => alertPointMatches(item, operator, threshold)).length
  return {
    id,
    type,
    title: `${metric}预警规则`,
    summary: '已生成离线批处理 Agent 轮询规则，确认保存后可用于后续异常检测与推送。',
    tableName,
    metric,
    timeRange: '每日上午 9:00 检测',
    status: '待确认',
    params: {
      operator,
      threshold,
      channel: params.channel || llmIntent.channel || 'both',
      timeField: params.timeField || llmIntent.timeField || '',
      metricField: params.metricField || llmIntent.metricField || llmIntent.metric || '',
      previewSource: usingRealSeries ? 'current-chart' : 'simulated'
    },
    explanation: buildAlertCardExplanation({
      operator,
      threshold,
      channels: [params.channel || llmIntent.channel || 'both'],
      detectionCycle: params.detectionCycle || llmIntent.detectionCycle || 'daily'
    }),
    series,
    insights: [
      { label: '阈值', value: formatAdvancedNumber(threshold) },
      { label: usingRealSeries ? '命中点' : '模拟异常', value: `${abnormalCount} 次` },
      { label: '检测方式', value: operator === 'zscore' ? 'Z-Score' : '阈值检测' }
    ]
  }
}

const chatHistoryRef = ref(null)
const chatScrollFrameIds = new Set()
let chatObservedDom = null
let chatMutationObserver = null
let chatResizeObserver = null

const getChatHistoryDom = () => chatHistoryRef.value || document.getElementById('chatHistory')

const scrollChatToBottomNow = () => {
  const dom = getChatHistoryDom()
  if (!dom) return
  dom.scrollTop = dom.scrollHeight
}

const scheduleChatScrollFrames = (frames = 3) => {
  if (frames <= 0 || typeof window === 'undefined' || typeof window.requestAnimationFrame !== 'function') return
  const frameId = window.requestAnimationFrame(() => {
    chatScrollFrameIds.delete(frameId)
    scrollChatToBottomNow()
    scheduleChatScrollFrames(frames - 1)
  })
  chatScrollFrameIds.add(frameId)
}

const scrollChatToBottom = () => {
  nextTick(() => {
    scrollChatToBottomNow()
    scheduleChatScrollFrames()
  })
}

const observeChatChildrenSize = (dom) => {
  if (!chatResizeObserver || !dom) return
  chatResizeObserver.disconnect()
  chatResizeObserver.observe(dom)
  Array.from(dom.children || []).forEach(child => chatResizeObserver.observe(child))
}

const setupChatAutoScrollObservers = () => {
  const dom = getChatHistoryDom()
  if (!dom) return
  if (chatObservedDom === dom) {
    observeChatChildrenSize(dom)
    return
  }
  chatMutationObserver?.disconnect()
  chatResizeObserver?.disconnect()
  chatObservedDom = dom
  if (typeof ResizeObserver !== 'undefined') {
    chatResizeObserver = new ResizeObserver(() => scrollChatToBottom())
    observeChatChildrenSize(dom)
  }
  if (typeof MutationObserver !== 'undefined') {
    chatMutationObserver = new MutationObserver(() => {
      observeChatChildrenSize(dom)
      scrollChatToBottom()
    })
    chatMutationObserver.observe(dom, { childList: true, subtree: true, characterData: true })
  }
}

const chatAutoScrollSignature = computed(() => {
  const rows = Array.isArray(messages?.value) ? messages.value : []
  return rows.map((msg, index) => [
    index,
    msg?.role || '',
    msg?.content || '',
    msg?.thinkingLogs?.length || 0,
    msg?.thinkingCollapsed === false ? 'open' : 'closed',
    msg?.sql ? String(msg.sql).length : 0,
    msg?.chartType || '',
    msg?.responseType || msg?.smartIntent || '',
    Array.isArray(msg?.data) ? msg.data.length : 0,
    Array.isArray(msg?.stepResults) ? msg.stepResults.length : 0,
    msg?.advancedAnalysis?.type || '',
    msg?.advancedAnalysis?.status || '',
    msg?.advancedAnalysis?.params?.eventId || '',
    Array.isArray(msg?.alertEventRows) ? msg.alertEventRows.length : 0
  ].join('|')).join('||')
})

onMounted(() => {
  setupChatAutoScrollObservers()
  scrollChatToBottom()
  renderSemanticKnowledgeGraph()
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', resizeSemanticKnowledgeGraph)
  }
})

watch(chatAutoScrollSignature, () => {
  setupChatAutoScrollObservers()
  scrollChatToBottom()
}, { flush: 'post' })

watch(activeModule, (moduleName) => {
  if (moduleName !== 'chat') return
  setupChatAutoScrollObservers()
  scrollChatToBottom()
}, { flush: 'post' })

const buildAdvancedIntentPayload = async (text, signal) => {
  const tableName = selectedTableName?.value || lastAnalysis?.value?.tableName || ''
  const selectedModelPayload = selectedChatModelPayload.value
  let fieldMeta = null
  if (tableName) {
    try {
      fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName }, signal ? { signal } : undefined)
    } catch (error) {
      console.warn('advanced analysis field meta unavailable:', error)
    }
  }
  return {
    question: text,
    tableName,
    context: {
      lastMetric: lastAnalysis?.value?.fieldMapping?.metric || '',
      lastMetricKey: lastAnalysis?.value?.fieldMapping?.metricKey || '',
      lastDimension: lastAnalysis?.value?.fieldMapping?.dimension || '',
      lastDimensionKey: lastAnalysis?.value?.fieldMapping?.dimensionKey || '',
      chartType: lastAnalysis?.value?.chartType || '',
      sourceQuestion: lastAnalysis?.value?.sourceQuestion || '',
      selectedTableName: tableName,
      activeBusinessModelId: activeBusinessModelId?.value ?? selectedChatBusinessModelId?.value ?? '',
      lastCreatedBusinessModelId: lastCreatedBusinessModelId?.value ?? '',
      lastAppliedBusinessModelId: lastAppliedBusinessModelId?.value ?? '',
      ...selectedModelPayload,
      fields: fieldMeta?.fields || [],
      timeFields: fieldMeta?.timeFields || [],
      numericFields: fieldMeta?.numericFields || []
    }
  }
}

const normalizeAdvancedParsedIntent = (parsed) => {
  const intent = normalizeAdvancedIntentType(parsed?.intent || parsed?.type)
  return intent ? { ...parsed, intent } : null
}

const parseAdvancedIntentWithLlm = async (text) => {
  try {
    const parsed = await parseAdvancedAnalysisIntent(await buildAdvancedIntentPayload(text))
    return normalizeAdvancedParsedIntent(parsed)
  } catch (error) {
    console.warn('advanced analysis llm parse fallback:', error)
    return null
  }
}

const parseAdvancedIntentWithStream = async (text, onThinking, signal) => {
  const parsed = await streamAdvancedAnalysisIntent(await buildAdvancedIntentPayload(text, signal), {
    onThinking,
    signal
  })
  return normalizeAdvancedParsedIntent(parsed)
}

const buildAdvancedAnalysisMessage = (analysis, userText = '', thinkingLogs = []) => {
  const normalized = withAdvancedChartRecommendation(analysis)
  return {
    role: 'system',
    content: `${advancedAnalysisTypeLabel(normalized.type)}已生成，请在卡片中调整参数、重新计算或保存方案。`,
    advancedAnalysis: normalized,
    sourceQuestion: userText,
    sourceTableName: normalized.tableName || selectedTableName?.value || '',
    thinkingLogs: Array.isArray(thinkingLogs) ? thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT) : [],
    thinkingCollapsed: true
  }
}

const nextAdvancedClientMessageId = () => {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `advanced-chat-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const applyAdvancedChatRecord = (message, record = {}) => {
  if (!message || !record || typeof record !== 'object') return
  const patch = {
    conversationId: record.conversationId == null ? undefined : String(record.conversationId),
    userTurnId: record.userTurnId == null ? undefined : String(record.userTurnId),
    assistantTurnId: record.assistantTurnId == null ? undefined : String(record.assistantTurnId),
    turnId: record.assistantTurnId == null ? undefined : String(record.assistantTurnId),
    artifactId: record.artifactId == null ? undefined : String(record.artifactId),
    artifactType: record.artifactType == null ? undefined : String(record.artifactType),
    historyId: record.historyId == null ? undefined : String(record.historyId),
    queryHistoryId: record.historyId == null ? undefined : String(record.historyId),
    chatRecordStatus: record.recorded === false ? 'failed' : 'saved',
    chatRecord: record
  }
  Object.entries(patch).forEach(([key, value]) => {
    if (value !== undefined) message[key] = value
  })
  if (message.advancedAnalysis && typeof message.advancedAnalysis === 'object') {
    message.advancedAnalysis = {
      ...message.advancedAnalysis,
      conversationId: patch.conversationId ?? message.advancedAnalysis.conversationId,
      userTurnId: patch.userTurnId ?? message.advancedAnalysis.userTurnId,
      assistantTurnId: patch.assistantTurnId ?? message.advancedAnalysis.assistantTurnId,
      artifactId: patch.artifactId ?? message.advancedAnalysis.artifactId,
      artifactType: patch.artifactType ?? message.advancedAnalysis.artifactType,
      historyId: patch.historyId ?? message.advancedAnalysis.historyId,
      queryHistoryId: patch.queryHistoryId ?? message.advancedAnalysis.queryHistoryId,
      chatRecord: record
    }
  }
  if (record.conversationId != null && activeChatSessionId?.value !== undefined) {
    activeChatSessionId.value = String(record.conversationId)
  }
}

const persistAdvancedAnalysisMessage = async (message, analysis, userText = '', thinkingLogs = [], llmIntent = {}) => {
  if (!analysis || !message || message.chatRecordStatus === 'saved' || message.chatRecordStatus === 'saving') return null
  message.chatRecordStatus = 'saving'
  const clientMessageId = message.clientMessageId || nextAdvancedClientMessageId()
  message.clientMessageId = clientMessageId
  const payload = {
    conversationId: activeChatSessionId?.value || undefined,
    parentTurnId: activeBranchParentTurnMeta?.value?.turnId || undefined,
    question: userText,
    tableName: analysis.tableName || selectedTableName?.value || '',
    type: analysis.type,
    analysis: withAdvancedChartRecommendation(analysis),
    message: message.content,
    llmIntent: llmIntent || {},
    thinkingLogs: Array.isArray(thinkingLogs) ? thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT) : [],
    clientMessageId
  }
  try {
    const record = await saveAdvancedAnalysisChatRecord(payload)
    applyAdvancedChatRecord(message, record)
    try {
      if (typeof syncChatSessionListItem === 'function') {
        syncChatSessionListItem({
          id: record?.conversationId || activeChatSessionId?.value,
          title: userText || analysis.title || '高级分析',
          summary: message.content,
          tableName: payload.tableName,
          latestAdvancedType: analysis.type,
          updatedAt: new Date().toISOString()
        })
      }
    } catch (syncError) {
      console.warn('sync advanced chat session item failed:', syncError)
    }
    return record
  } catch (error) {
    message.chatRecordStatus = 'failed'
    console.warn('save advanced analysis chat record failed:', error)
    ElMessage.warning('高级分析卡片已生成，但保存到会话历史失败，刷新后可能无法恢复')
    return null
  }
}

const pushAdvancedAnalysisMessage = (analysis, userText = '') => {
  messages.value.push(buildAdvancedAnalysisMessage(analysis, userText))
  scrollChatToBottom()
}

const replaceAlertDraftMessageWithCreatedRule = (message, analysis, savedRule) => {
  if (!message || !analysis) return
  const index = (messages.value || []).indexOf(message)
  const multiStepPatch = buildAlertCreatedMultiStepPatch(message, analysis, savedRule)
  const nextMessage = {
    ...message,
    ...multiStepPatch,
    content: '预警规则已创建，可在预警规则管理中查看和维护。',
    alertRuleDraft: null,
    alertRuleCreated: {
      id: savedRule?.id,
      title: analysis.title,
      metricField: analysis.params?.metricField || savedRule?.metricField || '',
      timeField: analysis.params?.timeField || savedRule?.timeField || '',
      operator: analysis.params?.operator || savedRule?.operator || '',
      threshold: analysis.params?.threshold ?? savedRule?.threshold,
      channels: analysis.params?.channels || savedRule?.channels || []
    },
    advancedAnalysis: withAdvancedChartRecommendation(analysis),
    analysisSnapshot: null,
    clickableChart: false,
    chatRecordStatus: 'saved'
  }
  if (index >= 0) {
    messages.value.splice(index, 1, nextMessage)
  } else {
    Object.assign(message, nextMessage)
  }
  return nextMessage
}

const persistAlertDraftCreatedState = async (message, analysis, savedRule) => {
  const conversationId = message?.conversationId || activeChatSessionId?.value
  const assistantTurnId = message?.turnId || message?.assistantTurnId
  if (!conversationId || !assistantTurnId) {
    return null
  }
  const alertRuleCreated = {
    id: savedRule?.id,
    title: analysis.title,
    metricField: analysis.params?.metricField || savedRule?.metricField || '',
    timeField: analysis.params?.timeField || savedRule?.timeField || '',
    operator: analysis.params?.operator || savedRule?.operator || '',
    threshold: analysis.params?.threshold ?? savedRule?.threshold,
    channels: analysis.params?.channels || savedRule?.channels || []
  }
  const multiStepPatch = buildAlertCreatedMultiStepPatch(message, analysis, savedRule)
  return axios.post(`${API_BASE}/api/chat/alert-rule-created`, {
    conversationId,
    assistantTurnId,
    artifactId: message?.artifactId || null,
    message: '预警规则已创建，可在预警规则管理中查看和维护。',
    alertRuleCreated,
    advancedAnalysis: withAdvancedChartRecommendation(analysis),
    stepResults: multiStepPatch.stepResults || [],
    actionPlan: multiStepPatch.actionPlan || {},
    multiStepSummary: multiStepPatch.multiStepSummary || {}
  }).then(unwrap)
}

const alertEventStatusLabel = (status) => {
  const value = String(status || 'OPEN').toUpperCase()
  if (value === 'ACK') return '已确认'
  if (value === 'CLOSED') return '已关闭'
  return '待处理'
}

const alertEventStatusTagType = (status) => {
  const value = String(status || 'OPEN').toUpperCase()
  if (value === 'ACK') return 'primary'
  if (value === 'CLOSED') return 'success'
  return 'warning'
}

const alertEventRowsForMessage = (msg = {}) => Array.isArray(msg?.alertEventRows)
  ? msg.alertEventRows.filter(row => row && typeof row === 'object')
  : []

const visibleAlertEventRows = (msg = {}) => alertEventRowsForMessage(msg).slice(0, 10)

const alertEventRuleName = (row = {}) => {
  const name = String(row.ruleName || row.ruleTitle || row.title || row.name || '').trim()
  if (name) return name
  const ruleId = String(row.ruleId || row.alertRuleId || '').trim()
  return ruleId ? `规则 #${ruleId}` : '-'
}

const parseAlertEventSnapshot = (value) => {
  if (!value) return {}
  if (typeof value === 'object' && !Array.isArray(value)) return value
  try {
    const parsed = JSON.parse(String(value))
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch (error) {
    return {}
  }
}

const alertEventIdentity = (row = {}) => String(row.id || row.eventId || row.alertEventId || '').trim()

const alertEventSnapshotSources = (row = {}, msg = {}) => {
  const analysis = msg?.analysisSnapshot || {}
  const sources = [row]
  if (analysis.alertEvent) sources.push(analysis.alertEvent)
  if (msg.alertEvent) sources.push(msg.alertEvent)
  ;[analysis.alertEvents, msg.alertEvents, analysis.data, msg.alertEventRows].forEach(list => {
    if (Array.isArray(list)) sources.push(...list)
  })
  return sources.filter(item => item && typeof item === 'object')
}

const resolveAlertEventSnapshotSource = (row = {}, msg = {}) => {
  const id = alertEventIdentity(row)
  const sources = alertEventSnapshotSources(row, msg)
  if (!id) return sources[0] || row
  return sources.find(item => alertEventIdentity(item) === id && Object.keys(parseAlertEventSnapshot(item.chartSnapshot || item.chartSnapshotJson)).length)
      || sources.find(item => alertEventIdentity(item) === id)
      || row
}

const openAlertEventSnapshot = (row = {}, msg = {}) => {
  const source = resolveAlertEventSnapshotSource(row, msg)
  const snapshot = parseAlertEventSnapshot(source.chartSnapshot || source.chartSnapshotJson)
  const rows = Array.isArray(snapshot.data) ? snapshot.data.filter(item => item && typeof item === 'object') : []
  if (!rows.length) {
    ElMessage.warning('该预警事件暂无可查看快照')
    return
  }
  const eventId = alertEventIdentity(source) || alertEventIdentity(row) || '-'
  const chartType = String(snapshot.chartType || snapshot.type || snapshot.chartOption?.series?.[0]?.type || 'line').toLowerCase()
  const tableName = String(snapshot.tableName || source.tableName || row.tableName || msg.sourceTableName || selectedTableName?.value || '').trim()
  const metric = String(snapshot.metricField || source.metricField || row.metricField || '预警指标').trim()
  const timeField = String(snapshot.timeField || source.timeField || row.timeField || '时间').trim()
  const analysis = {
    ...snapshot,
    responseType: 'ALERT_EVENT_SNAPSHOT',
    smartIntent: 'ALERT_EVENT_SNAPSHOT',
    chartType,
    recommendedChartType: chartType,
    tableName,
    sourceTableName: tableName,
    sourceQuestion: `查看预警事件 #${eventId} 快照`,
    message: `预警事件 #${eventId} 触发快照`,
    data: rows,
    fieldMapping: snapshot.fieldMapping || {
      mappingType: 'alert',
      metric,
      metricField: metric,
      metricKey: metric,
      dimension: timeField,
      dimensionKey: 'name'
    },
    optionTemplate: snapshot.optionTemplate || snapshot.chartOption || {}
  }
  lastAnalysis.value = analysis
  currentChartType.value = chartType
  if (tableName && selectedTableName?.value !== tableName) {
    selectedTableName.value = tableName
  }
  nextTick(() => {
    renderChart(rows, chartType)
  })
  ElMessage.success(`已打开预警事件 #${eventId} 快照`)
}

const alertPushStatusLabel = (status) => {
  const value = String(status || 'PENDING').toUpperCase()
  if (value === 'SUCCESS') return '推送成功'
  if (value === 'FAILED') return '推送失败'
  return '待推送'
}

const alertOperatorLabel = (operator) => {
  const value = String(operator || 'lt').toLowerCase()
  if (value === 'gt') return '高于阈值'
  if (value === 'zscore') return 'Z-Score 异常波动'
  return '低于阈值'
}

const findAdvancedField = (fieldName, fieldMeta = {}) => {
  const name = String(fieldName || '').trim()
  if (!name) return null
  const candidates = [
    ...(Array.isArray(fieldMeta.fields) ? fieldMeta.fields : []),
    ...(Array.isArray(fieldMeta.timeFields) ? fieldMeta.timeFields : []),
    ...(Array.isArray(fieldMeta.numericFields) ? fieldMeta.numericFields : []),
    ...(Array.isArray(fields?.value) ? fields.value : [])
  ]
  return candidates.find(item => [
    item?.columnName,
    item?.sourceFieldName,
    item?.displayName,
    item?.businessName
  ].some(value => String(value || '').trim() === name)) || null
}

const formatRestoredAlertField = (fieldName, fieldMeta = {}, fallback = '指标') => {
  const matched = findAdvancedField(fieldName, fieldMeta)
  return matched ? formatAdvancedFieldLabel(matched) : formatAnalysisMetricLabel(fieldName, fallback, fieldMeta.fields || fields?.value || [])
}

const buildRestoredAlertSeries = (event = {}) => {
  const snapshot = event.chartSnapshot || {}
  const rows = Array.isArray(snapshot.data) && snapshot.data.length
    ? snapshot.data
    : [{ bucketName: event.bucketName, name: event.bucketName || '触发点', value: event.actualValue }]
  return rows.map(item => ({
    name: String(item?.bucketName || item?.period || item?.date || item?.time || item?.triggeredAt || item?.createdAt || item?.name || '-'),
    bucketName: item?.bucketName,
    period: item?.period,
    date: item?.date,
    time: item?.time,
    triggeredAt: item?.triggeredAt,
    createdAt: item?.createdAt,
    value: Number(item?.value ?? item?.actualValue ?? item?.metricValue ?? item?.currentValue ?? 0),
    triggered: Boolean(item?.triggered)
  }))
}

const pushRestoredAdvancedAlertMessage = (analysis, context = {}) => {
  const eventId = analysis?.params?.eventId || '-'
  messages.value.push({
    role: 'user',
    content: `查看预警事件 #${eventId} 的上下文`,
    sourceTableName: analysis.tableName || selectedTableName?.value || ''
  })
  messages.value.push({
    role: 'system',
    content: `已从${context.sourceLabel || '预警管理'}恢复预警上下文，可继续追问触发原因、历史快照或规则调整建议。`,
    advancedAnalysis: analysis,
    sourceQuestion: `恢复预警事件 #${eventId}`,
    sourceTableName: analysis.tableName || selectedTableName?.value || ''
  })
  nextTick(() => {
    const dom = document.getElementById('chatHistory')
    if (dom) dom.scrollTop = dom.scrollHeight
  })
}

const restoreAdvancedAlertContext = async (context) => {
  if (!context) return
  if (advancedAlertContext?.value === context) {
    advancedAlertContext.value = null
  }
  const event = context.event || {}
  const pushLog = context.pushLog || null
  const tableName = String(event.tableName || '').trim()
  if (tableName && selectedTableName?.value !== tableName) {
    selectedTableName.value = tableName
  }
  chatContentMode.value = 'messages'
  let fieldMeta = {}
  if (tableName) {
    try {
      fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName })
    } catch (error) {
      console.warn('restore alert field meta failed:', error)
    }
  }
  const metricLabel = formatRestoredAlertField(event.metricField, fieldMeta, '预警指标')
  const timeFieldLabel = formatRestoredAlertField(event.timeField, fieldMeta, '时间字段')
  const operator = String(event.operator || 'lt').toLowerCase()
  const thresholdText = operator === 'zscore' ? 'Z-Score >= 3' : formatAdvancedNumber(event.threshold)
  const chartThreshold = operator === 'zscore'
    ? Number(event.baselineValue ?? event.actualValue ?? 0)
    : Number(event.threshold ?? 0)
  const pushChannel = pushLog?.channel ? formatAlertChannel([pushLog.channel]) : ''
  const calculation = [
    event.reason || '已恢复预警事件上下文，异常判断来自后端离线预警结果。',
    `数据源：${tableName || '-'}，指标：${metricLabel}，时间字段：${timeFieldLabel}，触发时间桶：${event.bucketName || '-'}。`,
    `判断条件：${alertOperatorLabel(operator)}，实际值 ${formatAdvancedNumber(event.actualValue)}，阈值 ${thresholdText}，历史基线 ${formatAdvancedNumber(event.baselineValue)}，Z-Score ${formatAdvancedNumber(event.zScore)}。`,
    pushLog ? `推送渠道：${pushChannel || '-'}，推送状态：${alertPushStatusLabel(pushLog.status)}。` : ''
  ].filter(Boolean)
  const analysis = {
    id: `alert-context-${event.id || pushLog?.id || Date.now()}`,
    type: 'alert',
    title: `${metricLabel}预警事件`,
    summary: `已恢复规则 #${event.ruleId || '-'}、数据源、指标和触发原因上下文。`,
    tableName,
    metric: metricLabel,
    timeRange: event.bucketName || '触发时间桶',
    status: alertEventStatusLabel(event.status),
    ruleId: event.ruleId,
    params: {
      eventId: event.id,
      ruleId: event.ruleId,
      tableName,
      timeField: event.timeField,
      timeFieldLabel,
      metricField: event.metricField,
      metricFieldLabel: metricLabel,
      bucketName: event.bucketName,
      operator,
      threshold: Number.isFinite(chartThreshold) ? chartThreshold : 0,
      actualValue: event.actualValue,
      baselineValue: event.baselineValue,
      zScore: event.zScore,
      deviationRate: event.deviationRate,
      channel: pushChannel || '按规则配置',
      pushStatus: pushLog?.status || '',
      pushChannel: pushLog?.channel || ''
    },
    explanation: {
      source: 'context',
      sourceLabel: context.sourceLabel || '预警上下文',
      calculation,
      suggestions: [
        '可以继续追问该事件的触发原因、历史趋势、快照数据或阈值调整建议。',
        '如需确认、关闭、重开或补充处理备注，请回到“预测与情景模拟”菜单处理预警事件。'
      ]
    },
    series: buildRestoredAlertSeries(event),
    insights: [
      { label: '事件ID', value: event.id || '-' },
      { label: '规则ID', value: event.ruleId || '-' },
      { label: '触发时间桶', value: event.bucketName || '-' },
      { label: '实际值', value: formatAdvancedNumber(event.actualValue) },
      { label: '阈值', value: thresholdText },
      { label: '推送状态', value: pushLog ? alertPushStatusLabel(pushLog.status) : '未从推送记录恢复' }
    ]
  }
  pushRestoredAdvancedAlertMessage(analysis, context)
}

if (advancedAlertContext) {
  watch(advancedAlertContext, async (context) => {
    if (!context) return
    await restoreAdvancedAlertContext(context)
  }, { immediate: true })
}

const openAdvancedAnalysisDialog = (analysis) => {
  activeAdvancedAnalysis.value = withAdvancedChartRecommendation(analysis)
  advancedAnalysisDialogVisible.value = true
}

const sendChatQuestion = async () => {
  const text = String(question?.value || '').trim()
  const followUpParentTurnId = String(activeBranchParentTurnMeta?.value?.turnId || '').trim()
  if (followUpParentTurnId) {
    await sendQuestion()
    return
  }
  if (isAlertOperationQuestion(text)) {
    await sendQuestion()
    return
  }
  if (shouldUseSmartMultiStepOrchestration(text)) {
    await sendQuestion()
    return
  }
  const localIntent = inferAdvancedIntent(text)
  if (!localIntent) {
    await sendQuestion()
    return
  }
  if (!selectedTableName?.value && !lastAnalysis?.value?.tableName) {
    ElMessage.warning('请先选择数据源，或先完成一轮普通查询后再发起预测/推演/预警')
    return
  }
  if (localIntent === 'whatIf') {
    try {
      assertWhatIfInstructionAllowed(text)
    } catch (error) {
      ElMessage.error(error.message || '业务公式不合法')
      return
    }
  }
  const requestId = activeChatRequestId?.value != null ? activeChatRequestId.value + 1 : Date.now()
  if (activeChatRequestId?.value != null) {
    activeChatRequestId.value = requestId
  }
  const isCurrentAdvancedRequest = () => activeChatRequestId?.value == null || activeChatRequestId.value === requestId
  stopRequested.value = false
  messages.value.push({
    role: 'user',
    content: text,
    parentTurnId: String(activeBranchParentTurnMeta?.value?.turnId || '').trim() || null
  })
  question.value = ''
  loading.value = true
  isStreaming.value = true
  const placeholderIndex = messages.value.length
  const thinkingLogs = []
  const seenThinkingSet = new Set()
  const pushThinkingLine = (title, detail = '') => {
    const line = [String(title || '').trim(), String(detail || '').trim()].filter(Boolean).join('：')
    if (!line || seenThinkingSet.has(line)) return
    seenThinkingSet.add(line)
    thinkingLogs.push(line)
    const current = messages.value[placeholderIndex] || { role: 'system' }
    messages.value.splice(placeholderIndex, 1, {
      ...current,
      content: `高级分析处理中（${thinkingLogs.length}步）· 当前：${line}`,
      thinkingLogs: thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT),
      thinkingCollapsed: false,
      sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
    })
    scrollChatToBottom()
  }
  messages.value.push({
    role: 'system',
    content: '正在识别高级分析意图...',
    thinkingLogs: [],
    thinkingCollapsed: false,
    sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
  })
  scrollChatToBottom()
  const controller = new AbortController()
  streamAbortController.value = controller
  try {
    pushThinkingLine('收到指令', text)
    let llmIntent = null
    try {
      llmIntent = await parseAdvancedIntentWithStream(
        text,
        (step) => pushThinkingLine(step?.title || step?.stage || '处理中', step?.detail || ''),
        controller.signal
      )
    } catch (streamError) {
      if (stopRequested.value || streamError?.name === 'AbortError') {
        const stopError = new Error('已手动停止本次生成')
        stopError.name = 'AbortError'
        throw stopError
      }
      pushThinkingLine('流式解析降级', streamError.message || 'SSE 通道暂不可用，切换普通解析')
      llmIntent = await parseAdvancedIntentWithLlm(text)
    }
    const intent = llmIntent?.intent || localIntent
    pushThinkingLine('确认分析场景', advancedAnalysisTypeLabel(intent))
    pushThinkingLine('准备执行分析', intent === 'forecast' ? '确认预测参数并生成预测曲线' : intent === 'whatIf' ? '确认推演参数并计算多场景结果' : '确认预警规则并保存检测配置')
    const analysis = await createAdvancedAnalysisAsync(intent, text, {}, llmIntent || {}, controller.signal)
    pushThinkingLine('生成分析卡片', '已完成图表推荐规则匹配和结果卡片渲染')
    if (!isCurrentAdvancedRequest() || stopRequested.value) {
      messages.value.splice(placeholderIndex, 1, {
        role: 'system',
        content: '已手动停止本次生成。',
        thinkingLogs: thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT),
        thinkingCollapsed: true,
        sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
      })
      return
    }
    const advancedMessage = buildAdvancedAnalysisMessage(analysis, text, thinkingLogs)
    messages.value.splice(placeholderIndex, 1, advancedMessage)
    await persistAdvancedAnalysisMessage(advancedMessage, analysis, text, thinkingLogs, llmIntent || {})
    clearActiveBranchParent()
    scrollChatToBottom()
  } catch (error) {
    if (error?.name === 'AbortError' || stopRequested.value) {
      messages.value.splice(placeholderIndex, 1, {
        role: 'system',
        content: '已手动停止本次生成。',
        thinkingLogs: thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT),
        thinkingCollapsed: true,
        sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
      })
      return
    }
    const message = error.message || '高级分析生成失败'
    ElMessage.error(message)
    messages.value.splice(placeholderIndex, 1, {
      role: 'system',
      content: `生成预测与情景模拟卡片失败：${message}`,
      thinkingLogs: thinkingLogs.slice(0, ADVANCED_THINKING_LOG_LIMIT),
      thinkingCollapsed: true,
      sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
    })
  } finally {
    if (streamAbortController.value === controller) {
      streamAbortController.value = null
    }
    loading.value = false
    isStreaming.value = false
    stopRequested.value = false
    scrollChatToBottom()
  }
}

const recalculateAdvancedAnalysis = async ({ analysis, params }) => {
  if (!analysis?.id) return
  let nextAnalysis
  try {
    nextAnalysis = await createAdvancedAnalysisAsync(analysis.type, analysis.title || '', params)
  } catch (error) {
    ElMessage.error(`重新计算失败：${error.message || '无法基于真实数据预测'}`)
    return
  }
  nextAnalysis.id = analysis.id
  nextAnalysis.title = analysis.title
  const target = (messages.value || []).find(item => item?.advancedAnalysis?.id === analysis.id)
  if (target) {
    target.advancedAnalysis = withAdvancedChartRecommendation(nextAnalysis)
  }
  if (activeAdvancedAnalysis.value?.id === analysis.id) {
    activeAdvancedAnalysis.value = withAdvancedChartRecommendation(nextAnalysis)
  }
  ElMessage.success('已根据最新参数重新计算')
}

const applyAdvancedAnalysisUpdate = (analysisId, nextAnalysis) => {
  if (!analysisId || !nextAnalysis) return
  const target = (messages.value || []).find(item => item?.advancedAnalysis?.id === analysisId)
  if (target) {
    target.advancedAnalysis = nextAnalysis
  }
  if (activeAdvancedAnalysis.value?.id === analysisId) {
    activeAdvancedAnalysis.value = nextAnalysis
  }
  const historyIndex = advancedAnalysisHistory.value.findIndex(item => item.id === analysisId)
  if (historyIndex >= 0) {
    advancedAnalysisHistory.value.splice(historyIndex, 1, {
      ...advancedAnalysisHistory.value[historyIndex],
      ...nextAnalysis
    })
  }
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

const explainAdvancedAnalysis = async (analysis) => {
  if (!analysis?.id) return
  advancedAnalysisExplainingId.value = analysis.id
  try {
    const explanation = await explainAdvancedAnalysisResult({
      type: analysis.type,
      question: analysis.title || '',
      result: buildAdvancedExplanationPayloadResult(analysis),
      context: {
        tableName: analysis.tableName || analysis.params?.tableName || selectedTableName?.value || '',
        metric: analysis.metric || '',
        source: 'chat-analysis-card',
        ...selectedChatModelPayload.value
      }
    })
  const nextAnalysis = {
      ...withAdvancedChartRecommendation(analysis),
      explanation
    }
    applyAdvancedAnalysisUpdate(analysis.id, nextAnalysis)
    ElMessage.success(explanation?.source === 'llm' ? 'AI 解释已生成' : '已生成规则解释兜底')
  } catch (error) {
    ElMessage.error(`生成解释失败：${error.message || '未知原因'}`)
  } finally {
    advancedAnalysisExplainingId.value = ''
  }
}

const buildAdvancedPlanRequest = (analysis = {}) => {
  const params = analysis.params || {}
  if (analysis.type === 'forecast') {
    const sourceSeries = Array.isArray(params.sourceSeries) && params.sourceSeries.length
      ? params.sourceSeries
      : []
    if (sourceSeries.length) {
      return {
        tableName: params.tableName || analysis.tableName || '',
        metric: analysis.metric || params.metricField || '核心指标',
        series: sourceSeries,
        horizon: Number(params.horizon || 3),
        algorithm: params.algorithm || 'Holt-Winters',
        alpha: params.alpha ?? params.algorithmParams?.alpha ?? 0.55,
        beta: params.beta ?? params.algorithmParams?.beta ?? 0.28,
        gamma: params.gamma ?? params.algorithmParams?.gamma ?? 0.20,
        seasonLength: params.seasonLength ?? params.algorithmParams?.seasonLength ?? 0
      }
    }
    return {
      tableName: params.tableName || analysis.tableName || '',
      timeField: params.timeField || '',
      metricField: params.metricField || '',
      granularity: params.granularity || 'month',
      filterExpression: params.filterExpression || '',
      horizon: Number(params.horizon || 3),
      algorithm: params.algorithm || 'Holt-Winters',
      alpha: params.alpha ?? params.algorithmParams?.alpha ?? 0.55,
      beta: params.beta ?? params.algorithmParams?.beta ?? 0.28,
      gamma: params.gamma ?? params.algorithmParams?.gamma ?? 0.20,
      seasonLength: params.seasonLength ?? params.algorithmParams?.seasonLength ?? 0
    }
  }
  if (analysis.type === 'whatIf') {
    return {
      tableName: params.tableName || analysis.tableName || '',
      targetMetric: params.targetMetric || '',
      formula: params.formula || '',
      formulaScope: params.formula ? params.formulaScope || 'aggregate' : 'aggregate',
      variables: Array.isArray(params.variables) ? params.variables.map(item => ({ ...item })) : []
    }
  }
  return {}
}

const buildAdvancedFieldMappingSnapshot = (analysis = {}) => {
  const params = analysis.params || {}
  if (analysis.type === 'forecast') {
    return {
      mappingType: 'forecast',
      confirmed: true,
      tableName: params.tableName || analysis.tableName || '',
      timeField: params.timeField || '',
      metricField: params.metricField || '',
      metricLabel: analysis.metric || params.metricField || '',
      granularity: params.granularity || analysis.timeRange || '',
      filterExpression: params.filterExpression || '',
      algorithm: params.algorithm || ''
    }
  }
  if (analysis.type === 'whatIf') {
    return {
      mappingType: 'whatIf',
      confirmed: true,
      tableName: params.tableName || analysis.tableName || '',
      targetMetric: params.targetMetric || '',
      metricLabel: analysis.metric || params.targetMetric || '',
      formula: params.formula || analysis.formula || '',
      resolvedFormula: params.resolvedFormula || analysis.resolvedFormula || '',
      formulaScope: params.formula || analysis.formula ? params.formulaScope || 'aggregate' : '',
      variables: Array.isArray(params.variables) ? params.variables.map(item => ({
        name: item.name || item.field || '',
        field: item.field || '',
        mode: item.mode || 'percent',
        change: item.change ?? 0,
        min: item.min ?? null,
        max: item.max ?? null
      })) : []
    }
  }
  return {}
}

const buildAdvancedPlanPayload = (analysis = {}) => ({
  planType: analysis.type,
  planName: analysis.title || advancedAnalysisTypeLabel(analysis.type),
  tableName: analysis.tableName || analysis.params?.tableName || '',
  metricLabel: analysis.metric || '',
  timeRangeLabel: analysis.timeRange || '',
  fieldMapping: buildAdvancedFieldMappingSnapshot(analysis),
  request: buildAdvancedPlanRequest(analysis),
  result: {
    ...analysis,
    params: analysis.params || {},
    series: Array.isArray(analysis.series) ? analysis.series : [],
    insights: Array.isArray(analysis.insights) ? analysis.insights : []
  },
  llm: {
    source: 'chat-card',
    savedAt: new Date().toISOString()
  }
})

const saveAdvancedAnalysis = async (analysis) => {
  if (!analysis?.id) return
  if (!['forecast', 'whatIf'].includes(analysis.type)) {
    ElMessage.info('预警规则已通过规则保存接口持久化，可在预测与情景模拟菜单管理')
    return
  }
  let persistedPlan = null
  try {
    persistedPlan = await saveAdvancedAnalysisPlan(buildAdvancedPlanPayload(analysis))
  } catch (error) {
    ElMessage.error(`保存方案失败：${error.message || '未知原因'}`)
    return
  }
  const savedAnalysis = {
    ...withAdvancedChartRecommendation(analysis),
    status: '已保存',
    planId: persistedPlan?.id || analysis.planId
  }
  const existsIndex = advancedAnalysisHistory.value.findIndex(item => item.id === analysis.id)
  const record = {
    ...savedAnalysis,
    createdAt: new Date().toLocaleString('zh-CN', { hour12: false }),
    persistedPlan
  }
  if (existsIndex >= 0) {
    advancedAnalysisHistory.value.splice(existsIndex, 1, record)
  } else {
    advancedAnalysisHistory.value.unshift(record)
  }
  const target = (messages.value || []).find(item => item?.advancedAnalysis?.id === analysis.id)
  if (target) {
    target.advancedAnalysis = savedAnalysis
  }
  if (activeAdvancedAnalysis.value?.id === analysis.id) {
    activeAdvancedAnalysis.value = savedAnalysis
  }
  ElMessage.success('已保存到预测与情景模拟方案资产')
}

watch(advancedHistoryVisible, (visible) => {
  if (visible) {
    loadAdvancedAlertRules()
    loadAdvancedAlertEvents()
  }
})

const loadAdvancedAlertRules = async () => {
  advancedAlertRulesLoading.value = true
  try {
    const rows = await listAdvancedAlertRules()
    advancedAlertRules.value = Array.isArray(rows)
      ? rows.filter(item => item.status !== 'DELETED')
      : []
  } catch (error) {
    ElMessage.error(`加载预警规则失败：${error.message || '未知原因'}`)
  } finally {
    advancedAlertRulesLoading.value = false
  }
}

const editAdvancedAlertRule = async (rule) => {
  if (!rule?.id) return
  try {
    const detail = await getAdvancedAlertRule(rule.id)
    const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName: detail.tableName })
    alertRuleEditorMeta.value = {
      timeFields: Array.isArray(fieldMeta?.timeFields) ? fieldMeta.timeFields : [],
      numericFields: Array.isArray(fieldMeta?.numericFields) ? fieldMeta.numericFields : []
    }
    alertRuleEditorForm.value = {
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
    alertRuleEditorVisible.value = true
  } catch (error) {
    ElMessage.error(`打开预警规则失败：${error.message || '未知原因'}`)
  }
}

const submitAlertRuleEditor = async () => {
  const form = alertRuleEditorForm.value
  if (!form.id || !form.timeField || !form.metricField) {
    ElMessage.warning('请选择时间字段和指标字段')
    return
  }
  if (!form.channels?.length) {
    ElMessage.warning('请至少选择一个通知渠道')
    return
  }
  alertRuleEditorSaving.value = true
  try {
    const updated = await updateAdvancedAlertRule(form)
    const index = advancedAlertRules.value.findIndex(item => String(item.id) === String(updated.id))
    if (index >= 0) {
      advancedAlertRules.value.splice(index, 1, updated)
    }
    alertRuleEditorVisible.value = false
    ElMessage.success('预警规则已更新')
  } catch (error) {
    ElMessage.error(`保存预警规则失败：${error.message || '未知原因'}`)
  } finally {
    alertRuleEditorSaving.value = false
  }
}

const toggleAdvancedAlertRule = async (rule) => {
  if (!rule?.id) return
  const nextStatus = rule.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    const updated = await updateAdvancedAlertRuleStatus({ id: rule.id, status: nextStatus })
    const index = advancedAlertRules.value.findIndex(item => String(item.id) === String(rule.id))
    if (index >= 0) {
      advancedAlertRules.value.splice(index, 1, updated)
    }
    ElMessage.success(nextStatus === 'ACTIVE' ? '预警规则已启用' : '预警规则已停用')
  } catch (error) {
    ElMessage.error(`更新预警规则状态失败：${error.message || '未知原因'}`)
  }
}

const removeAdvancedAlertRule = async (rule) => {
  if (!rule?.id) return
  try {
    await deleteAdvancedAlertRule({ id: rule.id })
    advancedAlertRules.value = advancedAlertRules.value.filter(item => String(item.id) !== String(rule.id))
    ElMessage.success('预警规则已删除')
  } catch (error) {
    ElMessage.error(`删除预警规则失败：${error.message || '未知原因'}`)
  }
}

const loadAdvancedAlertEvents = async () => {
  advancedAlertEventsLoading.value = true
  try {
    const rows = await listAdvancedAlertEvents()
    advancedAlertEvents.value = Array.isArray(rows) ? rows : []
  } catch (error) {
    ElMessage.error(`加载预警事件失败：${error.message || '未知原因'}`)
  } finally {
    advancedAlertEventsLoading.value = false
  }
}

const runAdvancedAlertRuleDetection = async (rule) => {
  if (!rule?.id) return
  try {
    const result = await runAdvancedAlertDetection({ ruleId: rule.id })
    await loadAdvancedAlertEvents()
    ElMessage.success(`检测完成，新增 ${result?.createdEvents || 0} 条预警事件`)
  } catch (error) {
    ElMessage.error(`执行预警检测失败：${error.message || '未知原因'}`)
  }
}

const hasPinnableAnalysisSource = (analysis = {}) => Boolean(
  analysis?.artifactId
  || analysis?.assistantTurnId
  || analysis?.turnId
  || analysis?.queryHistoryId
  || analysis?.chartId
  || analysis?.historyId
)

const normalizePinAdvancedType = (analysis = {}) => {
  const raw = String(analysis?.type || analysis?.advancedAnalysisType || analysis?.chartSnapshot?.advancedAnalysisType || '').replace(/[-_\s]/g, '').toLowerCase()
  if (raw.includes('what')) return 'whatIf'
  if (raw.includes('alert') || raw.includes('warning')) return 'alert'
  if (raw.includes('forecast') || raw.includes('predict')) return 'forecast'
  return ''
}

const hasAdvancedAnalysisArtifactSource = (analysis = {}) => Boolean(
  analysis?.artifactId
  || analysis?.assistantTurnId
  || analysis?.turnId
)

const mergeAdvancedRecordIntoPinSource = (source = {}, record = {}) => ({
  ...source,
  conversationId: record.conversationId == null ? source.conversationId : String(record.conversationId),
  userTurnId: record.userTurnId == null ? source.userTurnId : String(record.userTurnId),
  assistantTurnId: record.assistantTurnId == null ? source.assistantTurnId : String(record.assistantTurnId),
  turnId: record.assistantTurnId == null ? source.turnId : String(record.assistantTurnId),
  artifactId: record.artifactId == null ? source.artifactId : String(record.artifactId),
  artifactType: record.artifactType || source.artifactType,
  queryHistoryId: record.historyId == null ? source.queryHistoryId : String(record.historyId),
  historyId: record.historyId == null ? source.historyId : String(record.historyId)
})

const findAdvancedAnalysisMessage = (analysisId) =>
  (messages.value || []).find(item => item?.advancedAnalysis?.id === analysisId)

const mergeMessageContextIntoPinSource = (source = {}, message = {}) => ({
  ...source,
  ...(message.advancedAnalysis || {}),
  sourceQuestion: message.sourceQuestion || source.sourceQuestion || source.title || '',
  sourceTableName: message.sourceTableName || source.sourceTableName || source.tableName || '',
  conversationId: message.conversationId || source.conversationId,
  userTurnId: message.userTurnId || source.userTurnId,
  assistantTurnId: message.assistantTurnId || message.turnId || source.assistantTurnId,
  turnId: message.turnId || message.assistantTurnId || source.turnId,
  artifactId: message.artifactId || source.artifactId,
  artifactType: message.artifactType || source.artifactType,
  queryHistoryId: message.queryHistoryId || message.historyId || source.queryHistoryId,
  historyId: message.historyId || message.queryHistoryId || source.historyId
})

const ensureAdvancedAnalysisPinnableSource = async (analysis) => {
  let source = withAdvancedChartRecommendation(analysis)
  const targetMessage = findAdvancedAnalysisMessage(analysis.id)
  if (targetMessage?.advancedAnalysis) {
    source = mergeMessageContextIntoPinSource(source, targetMessage)
  }
  const advancedType = normalizePinAdvancedType(source)
  if ((!advancedType && hasPinnableAnalysisSource(source)) || (advancedType && hasAdvancedAnalysisArtifactSource(source))) {
    return { source, targetMessage }
  }

  const userText = targetMessage?.sourceQuestion || source.sourceQuestion || source.title || '高级分析图表'
  const thinkingLogs = Array.isArray(targetMessage?.thinkingLogs) ? targetMessage.thinkingLogs : []
  const messageForPersist = targetMessage && !['saved', 'saving'].includes(targetMessage.chatRecordStatus)
    ? targetMessage
    : buildAdvancedAnalysisMessage(source, userText, thinkingLogs)
  const record = await persistAdvancedAnalysisMessage(
    messageForPersist,
    source,
    userText,
    thinkingLogs,
    source.llmIntent || {}
  )
  if (record) {
    source = mergeAdvancedRecordIntoPinSource(source, record)
  }
  if (targetMessage?.advancedAnalysis) {
    targetMessage.advancedAnalysis = {
      ...targetMessage.advancedAnalysis,
      ...source
    }
    targetMessage.conversationId = source.conversationId || targetMessage.conversationId
    targetMessage.userTurnId = source.userTurnId || targetMessage.userTurnId
    targetMessage.assistantTurnId = source.assistantTurnId || targetMessage.assistantTurnId
    targetMessage.turnId = source.turnId || targetMessage.turnId
    targetMessage.artifactId = source.artifactId || targetMessage.artifactId
    targetMessage.artifactType = source.artifactType || targetMessage.artifactType
    targetMessage.historyId = source.historyId || targetMessage.historyId
    targetMessage.queryHistoryId = source.queryHistoryId || targetMessage.queryHistoryId
  }
  if ((!advancedType && !hasPinnableAnalysisSource(source)) || (advancedType && !hasAdvancedAnalysisArtifactSource(source))) {
    throw new Error('当前预测卡片还没有生成可钉入的会话产物，请重新生成预测后再试')
  }
  return { source, targetMessage }
}

const pinAdvancedAnalysis = async (analysis) => {
  if (!analysis?.id) return
  try {
    const { source, targetMessage } = await ensureAdvancedAnalysisPinnableSource(analysis)
    const opened = await openPinDialogForAnalysis?.(source)
    if (!opened) return
    advancedAnalysisDialogVisible.value = false
    if (targetMessage?.advancedAnalysis) {
      targetMessage.advancedAnalysis = source
    }
    if (activeAdvancedAnalysis.value?.id === analysis.id) {
      activeAdvancedAnalysis.value = source
    }
  } catch (error) {
    ElMessage.error(error.message || '打开钉入看板失败')
  }
}

const restoreAdvancedAnalysis = (analysis) => {
  advancedHistoryVisible.value = false
  chatContentMode.value = 'messages'
  const restored = { ...analysis, id: `advanced-${Date.now()}` }
  pushAdvancedAnalysisMessage(restored, analysis.title)
  openAdvancedAnalysisDialog(restored)
}

const removeAdvancedAnalysisHistory = (analysis) => {
  advancedAnalysisHistory.value = advancedAnalysisHistory.value.filter(item => item.id !== analysis.id)
}

const currentChatSessionTitle = computed(() =>
  String(currentChatSession.value?.title || '').trim() || '新对话'
)

const currentChatSessionSubtitle = computed(() => {
  if (!currentChatSession.value) {
    return '当前还没有激活会话，发送问题后会自动创建。'
  }
  const turnCount = Number(currentChatSession.value?.turnCount || 0)
  const updatedAt = currentChatSession.value?.updatedAt
    ? formatChatHistoryTime(currentChatSession.value.updatedAt)
    : ''
  const parts = [`${turnCount}轮`]
  if (updatedAt) parts.push(updatedAt)
  if (currentChatSession.value?.summary) {
    parts.push(String(currentChatSession.value.summary).trim().slice(0, 32))
  }
  return parts.join(' · ')
})

const isMessageChartRestorable = (msg) => {
  if (!msg || msg.role !== 'system') return false
  const snapshot = msg.analysisSnapshot
  if (msg.clickableChart) return true
  if (snapshot && Array.isArray(snapshot.data) && snapshot.data.length) return true
  return Boolean(
    msg.queryHistoryId
    || msg.chartId
    || msg.historyId
    || snapshot?.queryHistoryId
    || snapshot?.chartId
    || snapshot?.historyId
  )
}

const historyDrawerVisible = ref(false)
const selectedHistoryId = ref('')
const historyDetailLoading = ref(false)
const historyPreviewChartRef = ref(null)
const historyQuickDateRange = ref('')
const historyReplayTimer = ref(null)
const historyReplayVisibleCount = ref(0)
const historyReplayPlaying = ref(false)
let historyPreviewChartInstance = null

const historyQuickDateOptions = [
  { label: '全部', value: '' },
  { label: '今日', value: 'today' },
  { label: '本周', value: 'week' },
  { label: '本月', value: 'month' }
]

const historyTableOptions = computed(() => [
  ...(uploadTables?.value || []),
  ...(officialQueryTables?.value || [])
])

const openHistoryDrawer = async () => {
  historyDrawerVisible.value = true
  await searchRecentChatQueries()
  await loadPinnedHistoryIds()
}

const openAdvancedHistoryDrawer = async () => {
  advancedHistoryVisible.value = true
  await Promise.allSettled([
    loadAdvancedAlertRules(),
    loadAdvancedAlertEvents()
  ])
}

const openAdvancedAnalysisManagePage = () => {
  if (activeModule?.value !== undefined) {
    activeModule.value = 'advancedAnalysis'
  }
}

const syncHistorySearch = async () => {
  await searchRecentChatQueries()
}

const resetHistoryKeyword = async () => {
  historyQuickDateRange.value = ''
  await resetRecentChatQuerySearch()
}

const formatHistoryDatePart = (date) => {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

const buildHistoryQuickDateRange = (mode) => {
  const now = new Date()
  if (Number.isNaN(now.getTime())) return []
  const current = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  if (mode === 'today') {
    const value = formatHistoryDatePart(current)
    return [value, value]
  }
  if (mode === 'week') {
    const day = current.getDay() || 7
    const start = new Date(current)
    start.setDate(current.getDate() - day + 1)
    return [formatHistoryDatePart(start), formatHistoryDatePart(current)]
  }
  if (mode === 'month') {
    const start = new Date(current.getFullYear(), current.getMonth(), 1)
    return [formatHistoryDatePart(start), formatHistoryDatePart(current)]
  }
  return []
}

const applyHistoryQuickDateRange = async (value) => {
  const mode = String(value || '').trim()
  recentChatQueryDateRange.value = buildHistoryQuickDateRange(mode)
  await searchRecentChatQueries()
}

const toggleHistorySortDirection = async () => {
  recentChatQuerySortDirection.value = recentChatQuerySortDirection.value === 'ASC' ? 'DESC' : 'ASC'
  await searchRecentChatQueries()
}

const historyChartTypeLabel = (type) => {
  const text = String(type || '').trim().toLowerCase()
  if (text === 'bar') return '柱状图'
  if (text === 'line') return '折线图'
  if (text === 'pie' || text === 'doughnut' || text === 'donut') return '饼图'
  if (text === 'radar') return '雷达图'
  if (text === 'scatter') return '散点图'
  if (text === 'metric' || text === 'card' || text === 'kpi' || text === 'indicator') return '指标卡'
  if (text === 'map') return '地图'
  if (text === 'table') return '表格'
  if (text === 'alert' || text === 'advanced_alert') return '智能预警'
  return text || '图表'
}

const hasHistoryFilters = computed(() => Boolean(
  String(recentChatQueryKeyword?.value || '').trim()
  || String(recentChatQueryTableName?.value || '').trim()
  || String(recentChatQueryChartType?.value || '').trim()
  || String(recentChatQueryRiskLevel?.value || '').trim()
  || String(recentChatQueryExecutionStatus?.value || '').trim()
  || (Array.isArray(recentChatQueryDateRange?.value) && recentChatQueryDateRange.value.filter(Boolean).length)
))

const selectedHistoryEntry = computed(() =>
  (recentChatQueries?.value || []).find(item => String(item?.id || '') === String(selectedHistoryId.value || '')) || null
)

const historyEmptyDescription = computed(() => {
  if (recentChatQueryError?.value) return recentChatQueryError.value
  return hasHistoryFilters.value ? '没有找到匹配的历史产物' : '暂无历史产物'
})

const historyEmptyTitle = computed(() => {
  if (recentChatQueryError?.value) {
    return recentChatQueryErrorType?.value === 'search' ? '搜索失败' : '加载失败'
  }
  return hasHistoryFilters.value ? '暂无匹配结果' : '暂无历史产物'
})

const summarizeFieldMapping = (mapping) => {
  if (!mapping || typeof mapping !== 'object') return []
  return [
    { label: '维度', value: String(mapping.dimension || mapping.dimensionKey || '').trim() },
    { label: '指标', value: String(mapping.metric || mapping.metricKey || '').trim() },
    { label: '维度字段', value: String(mapping.dimensionKey || '').trim() },
    { label: '指标字段', value: String(mapping.metricKey || '').trim() }
  ].filter(item => item.value)
}

const summarizeGraphContext = (entry) => {
  const rows = Array.isArray(entry?.graphContext) ? entry.graphContext : []
  return rows.slice(0, 3).map(item => {
    if (typeof item === 'string') return item
    return String(item?.label || item?.name || item?.title || item?.id || '').trim()
  }).filter(Boolean)
}

const historySensitiveFields = (entry) => normalizeAuditList(entry?.sensitiveFields || entry?.chartSnapshot?.sensitiveFields)

const firstHistoryText = (...values) => {
  for (const value of values) {
    const text = String(value ?? '').trim()
    if (text) return text
  }
  return ''
}

const historyAlertObject = (entry, key) => {
  const direct = entry?.[key]
  if (direct && typeof direct === 'object' && !Array.isArray(direct)) return direct
  const snap = entry?.chartSnapshot || {}
  const nested = snap?.[key]
  return nested && typeof nested === 'object' && !Array.isArray(nested) ? nested : {}
}

const isHistoryAlertEntry = (entry) => {
  if (!entry) return false
  if (entry?.isAlertHistory) return true
  const signals = [
    entry?.advancedAnalysisType,
    entry?.chartType,
    entry?.artifactType,
    entry?.intentType,
    entry?.chartSnapshot?.advancedAnalysisType,
    entry?.chartSnapshot?.type,
    entry?.chartSnapshot?.chartType
  ].map(value => String(value || '').replace(/[-_\s]/g, '').toLowerCase())
  if (signals.some(value => value.includes('alert') || value.includes('warning') || value.includes('prewarning'))) {
    return true
  }
  const snap = entry?.chartSnapshot || {}
  return Boolean(
    Object.keys(historyAlertObject(entry, 'alertMeta')).length
      || Object.keys(historyAlertObject(entry, 'alertRule')).length
      || Object.keys(historyAlertObject(entry, 'alertRuleCreated')).length
      || Object.keys(historyAlertObject(entry, 'alertRuleDraft')).length
      || snap.ruleId
      || snap.eventId
  )
}

const historyAlertStatusLabel = (status) => {
  const text = String(status || '').trim()
  const upper = text.toUpperCase()
  if (['ACTIVE', 'ENABLED', '已启用'].includes(upper) || text === '已启用') return '已启用'
  if (['DISABLED', 'PAUSED', '停用', '已停用'].includes(upper) || text === '已停用') return '已停用'
  if (['CREATED', 'SAVED', 'SUCCESS', '已创建', '已保存'].includes(upper) || text === '已创建' || text === '已保存') return '已创建'
  if (['DRAFT', 'PENDING', '待确认'].includes(upper) || text === '待确认') return '待确认'
  return text || '已生成'
}

const historyAlertInfo = (entry) => {
  const snap = entry?.chartSnapshot || {}
  const meta = historyAlertObject(entry, 'alertMeta')
  const rule = historyAlertObject(entry, 'alertRule')
  const created = historyAlertObject(entry, 'alertRuleCreated')
  const draft = historyAlertObject(entry, 'alertRuleDraft')
  const params = snap?.params && typeof snap.params === 'object' ? snap.params : {}
  const threshold = created.threshold ?? rule.threshold ?? draft.threshold ?? meta.threshold ?? params.threshold ?? snap.threshold
  const channels = created.channels ?? rule.channels ?? draft.channels ?? meta.channels ?? params.channels ?? params.channel ?? snap.channels
  return {
    created,
    draft,
    ruleName: firstHistoryText(created.title, created.ruleName, rule.title, rule.ruleName, draft.title, draft.ruleName, meta.ruleName, snap.title, entry?.question),
    ruleId: firstHistoryText(created.id, created.ruleId, rule.id, rule.ruleId, meta.ruleId, params.ruleId, snap.ruleId),
    metricField: firstHistoryText(created.metricField, rule.metricField, draft.metricField, meta.metricField, params.metricField, snap.metricField),
    timeField: firstHistoryText(created.timeField, rule.timeField, draft.timeField, meta.timeField, params.timeField, snap.timeField),
    operator: firstHistoryText(created.operator, rule.operator, draft.operator, meta.operator, params.operator, snap.operator),
    threshold,
    detectionCycle: firstHistoryText(created.detectionCycle, rule.detectionCycle, draft.detectionCycle, meta.detectionCycle, params.detectionCycle, snap.detectionCycle),
    channels,
    status: firstHistoryText(created.status, rule.status, draft.status, meta.status, params.status, snap.status),
    triggerResult: firstHistoryText(meta.triggerResult, snap.triggerResult, snap.reason, entry?.riskReason)
  }
}

const historySnapshotStatusLabel = (entry) => {
  if (isHistoryAlertEntry(entry)) return '预警记录'
  const status = String(entry?.snapshotStatus || '').trim()
  if (status === 'ready') return '可恢复'
  if (status === 'missing') return '缺失'
  if (status === 'error') return '异常'
  return entry?.hasChartSnapshot ? '可恢复' : '缺失'
}

const historySnapshotStatusType = (entry) => {
  if (isHistoryAlertEntry(entry)) return 'warning'
  const status = String(entry?.snapshotStatus || '').trim()
  if (status === 'ready') return 'primary'
  if (status === 'missing') return 'info'
  if (status === 'error') return 'danger'
  return entry?.hasChartSnapshot ? 'primary' : 'info'
}

const historyExecutionStatusLabel = (entry) => {
  if (isHistoryAlertEntry(entry)) {
    const info = historyAlertInfo(entry)
    if (Object.keys(info.created || {}).length || info.ruleId) return `规则${historyAlertStatusLabel(info.status || 'CREATED')}`
    if (Object.keys(info.draft || {}).length) return '规则待确认'
    return '预警已生成'
  }
  const status = Number(entry?.executionStatus)
  if (status === 1) return '执行成功'
  if (status === 0) return '执行失败'
  if (status === 2) return '已取消'
  return '未知'
}

const historyExecutionStatusType = (entry) => {
  if (isHistoryAlertEntry(entry)) {
    const info = historyAlertInfo(entry)
    return Object.keys(info.draft || {}).length && !Object.keys(info.created || {}).length ? 'warning' : 'success'
  }
  const status = Number(entry?.executionStatus)
  if (status === 1) return 'success'
  if (status === 0) return 'danger'
  if (status === 2) return 'warning'
  return 'info'
}

const historyCacheLabel = (entry) => isHistoryAlertEntry(entry) ? '无需缓存' : (entry?.isHitCache ? '命中缓存' : '未命中缓存')
const historyCacheTagType = (entry) => entry?.isHitCache && !isHistoryAlertEntry(entry) ? 'primary' : 'info'
const isHistoryEntryRestorable = (entry) => Boolean(entry?.hasChartSnapshot)
const isHistoryTableEntry = (entry) => String(entry?.chartType || entry?.chartSnapshot?.chartType || '').toLowerCase() === 'table'

const formatHistoryValue = (value) => {
  if (value == null || value === '') return '--'
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(item => formatHistoryValue(item)).join('、')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const formatHistoryExecutionTime = (value, entry = null) => {
  if (isHistoryAlertEntry(entry)) return '不适用'
  const duration = Number(value)
  if (!Number.isFinite(duration) || duration < 0) return '未知'
  if (duration < 1000) return `${duration} ms`
  if (duration < 60000) return `${(duration / 1000).toFixed(duration >= 10000 ? 0 : 1)} s`
  return `${(duration / 60000).toFixed(1)} min`
}

const toChartNumber = (value) => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  const parsed = Number(String(value ?? '').replace(/,/g, '').trim())
  return Number.isFinite(parsed) ? parsed : 0
}

const sortHistoryChartRows = (rows) => {
  const normalizedRows = Array.isArray(rows) ? rows : []
  return [...normalizedRows].sort((left, right) => toChartNumber(right?.value) - toChartNumber(left?.value))
}

const buildHistoryPreviewSeriesData = (entry) => {
  const rows = sortHistoryChartRows(Array.isArray(entry?.chartSnapshot?.data) ? entry.chartSnapshot.data : []).slice(0, 8)
  return rows.map(row => ({
    name: String(row?.name ?? row?.dim_name ?? row?.dimension ?? '--'),
    value: toChartNumber(row?.value ?? row?.metric_value ?? row?.metric ?? 0)
  }))
}

const historySnapshotPreviewRows = (entry) => {
  const rows = Array.isArray(entry?.chartSnapshot?.tableRows)
    ? entry.chartSnapshot.tableRows
    : (Array.isArray(entry?.chartSnapshot?.data) ? entry.chartSnapshot.data : [])
  return rows.slice(0, 3).map(row => (row && typeof row === 'object' ? row : { value: row }))
}

const historySnapshotColumnLabels = (entry) => {
  const labels = {}
  const columns = Array.isArray(entry?.chartSnapshot?.tableColumns) ? entry.chartSnapshot.tableColumns : []
  for (const column of columns) {
    if (column && typeof column === 'object') {
      const key = String(column.prop || column.key || column.column || '').trim()
      if (key) labels[key] = String(column.label || column.name || key).trim() || key
    } else {
      const key = String(column || '').trim()
      if (key) labels[key] = key
    }
  }
  const fieldLabels = entry?.fieldMapping?.fieldResolution?.tableColumnLabels
  if (fieldLabels && typeof fieldLabels === 'object') {
    for (const [key, value] of Object.entries(fieldLabels)) {
      if (!labels[key]) labels[key] = String(value || key).trim() || key
    }
  }
  return labels
}

const historyReadableColumnLabel = (entry, key) => {
  const raw = String(key || '').trim()
  if (!raw) return '字段'
  const labels = historySnapshotColumnLabels(entry)
  const label = String(labels[raw] || '').trim()
  if (label && !/^col[_-]?\d+$/i.test(label)) return label
  return label || raw
}

const historySnapshotPreviewColumns = (entry) => {
  const rows = historySnapshotPreviewRows(entry)
  const keys = Array.isArray(entry?.chartSnapshot?.tableColumns) && entry.chartSnapshot.tableColumns.length
    ? entry.chartSnapshot.tableColumns.map(column => String(column?.prop || column?.key || column?.column || column || '').trim()).filter(Boolean)
    : Object.keys(rows[0] || {})
  return [...new Set(keys)].filter(key => !rows.length || Object.prototype.hasOwnProperty.call(rows[0], key))
    .map(key => ({ key, label: historyReadableColumnLabel(entry, key) }))
}

const historySnapshotTableMinWidth = (entry) => {
  const count = historySnapshotPreviewColumns(entry).length
  return `${Math.max(520, count * 132)}px`
}

const summarizeHistoryRule = (entry) => {
  const snap = entry?.chartSnapshot || {}
  if (isHistoryAlertEntry(entry)) {
    const info = historyAlertInfo(entry)
    const hasChannels = Array.isArray(info.channels)
      ? info.channels.length > 0
      : firstHistoryText(info.channels)
    const condition = [
      info.operator ? alertOperatorLabel(info.operator) : '',
      info.threshold != null && info.threshold !== '' ? formatAdvancedNumber(info.threshold) : ''
    ].filter(Boolean).join(' ')
    return [
      { label: '规则名称', value: info.ruleName },
      { label: '规则ID', value: info.ruleId },
      { label: '指标字段', value: info.metricField },
      { label: '时间字段', value: info.timeField },
      { label: '触发条件', value: condition },
      { label: '检测周期', value: info.detectionCycle ? alertCycleLabel(info.detectionCycle) : '' },
      { label: '通知渠道', value: hasChannels ? formatAlertChannel(info.channels) : '' },
      { label: '状态', value: historyAlertStatusLabel(info.status) },
      { label: '触发结果', value: info.triggerResult }
    ].filter(item => item.value)
  }
  const scenarioType = entry?.chartScenarioType || snap.chartScenarioType
  return [
    { label: '命中规则', value: String(entry?.chartRuleName || snap.chartRuleName || entry?.chartRuleCode || snap.chartRuleCode || '').trim() },
    { label: '规则编码', value: String(entry?.chartRuleCode || snap.chartRuleCode || '').trim() },
    { label: '推荐场景', value: scenarioType ? chartRecommendationScenarioLabel(scenarioType) : '' },
    { label: '推荐状态', value: formatChartRecommendationStatus(entry?.chartRecommendationStatus || snap.chartRecommendationStatus) },
    {
      label: '推荐说明',
      value: formatChartRecommendationExplain(
        entry?.chartRecommendationExplain || snap.chartRecommendationExplain,
        {
          ruleCode: entry?.chartRuleCode || snap.chartRuleCode,
          ruleName: entry?.chartRuleName || snap.chartRuleName,
          scenarioType: entry?.chartScenarioType || snap.chartScenarioType,
          status: entry?.chartRecommendationStatus || snap.chartRecommendationStatus
        }
      )
    }
  ].filter(item => item.value)
}

const historySnapshotPreviewCards = (entry) => {
  const rows = historySnapshotPreviewRows(entry)
  const dimensionKey = String(entry?.fieldMapping?.dimensionKey || '').trim()
  const metricKey = String(entry?.fieldMapping?.metricKey || '').trim()
  return rows.map((row, index) => {
    const keys = Object.keys(row || {})
    const titleKey = keys.find(key => key === dimensionKey) || keys[0] || ''
    const metricFieldKey = keys.find(key => key === metricKey) || keys.find(key => key !== titleKey) || ''
    const extraFieldKeys = keys.filter(key => key !== titleKey && key !== metricFieldKey).slice(0, 3)
    return {
      id: `${entry?.id || 'history'}-preview-${index}`,
      titleLabel: titleKey ? historyReadableColumnLabel(entry, titleKey) : '维度',
      titleValue: titleKey ? formatHistoryValue(row?.[titleKey]) : `第${index + 1}条`,
      metricLabel: metricFieldKey ? historyReadableColumnLabel(entry, metricFieldKey) : '指标',
      metricValue: metricFieldKey ? formatHistoryValue(row?.[metricFieldKey]) : '--',
      extraFields: extraFieldKeys.map(key => ({
        label: historyReadableColumnLabel(entry, key),
        value: formatHistoryValue(row?.[key])
      }))
    }
  })
}

const historyRestoreHint = (entry) => {
  if (isHistoryAlertEntry(entry)) return '预警历史记录用于查看规则详情，不作为普通图表恢复'
  const status = String(entry?.snapshotStatus || '').trim()
  if (status === 'missing') return '该历史记录暂无可恢复的图表快照'
  if (status === 'error') return '图表快照加载异常，可稍后重试'
  return '该历史记录暂无可恢复的图表快照'
}

const historyReplaySteps = computed(() => {
  const entry = selectedHistoryEntry.value
  const steps = Array.isArray(entry?.reasoningReplaySteps) && entry.reasoningReplaySteps.length
    ? entry.reasoningReplaySteps
    : (Array.isArray(entry?.reasoningProcess)
      ? entry.reasoningProcess.map((step, index) => ({
        title: `步骤 ${index + 1}`,
        detail: String(step || '').trim()
      })).filter(step => step.detail)
      : [])
  return steps.map((step, index) => ({
    title: String(step?.title || `步骤 ${index + 1}`).trim(),
    detail: String(step?.detail || step?.text || step?.message || '').trim(),
    ts: step?.ts ?? null
  })).filter(step => step.title || step.detail)
})

const clearHistoryReplayTimer = () => {
  if (historyReplayTimer.value) {
    window.clearTimeout(historyReplayTimer.value)
    historyReplayTimer.value = null
  }
}

const scheduleHistoryReplay = () => {
  clearHistoryReplayTimer()
  if (!historyReplayPlaying.value) return
  if (historyReplayVisibleCount.value >= historyReplaySteps.value.length) {
    historyReplayPlaying.value = false
    return
  }
  historyReplayTimer.value = window.setTimeout(() => {
    historyReplayVisibleCount.value += 1
    scheduleHistoryReplay()
  }, 560)
}

const startHistoryReplay = () => {
  if (!historyReplaySteps.value.length) {
    historyReplayPlaying.value = false
    historyReplayVisibleCount.value = 0
    return
  }
  historyReplayPlaying.value = true
  if (historyReplayVisibleCount.value <= 0) {
    historyReplayVisibleCount.value = 1
  }
  scheduleHistoryReplay()
}

const pauseHistoryReplay = () => {
  historyReplayPlaying.value = false
  clearHistoryReplayTimer()
}

const restartHistoryReplay = () => {
  clearHistoryReplayTimer()
  historyReplayVisibleCount.value = 0
  startHistoryReplay()
}

const toggleHistoryReplay = () => {
  if (!historyReplaySteps.value.length) return
  if (historyReplayPlaying.value) {
    pauseHistoryReplay()
  } else {
    if (historyReplayVisibleCount.value >= historyReplaySteps.value.length) {
      historyReplayVisibleCount.value = 0
    }
    startHistoryReplay()
  }
}

const revealAllHistoryReplaySteps = () => {
  pauseHistoryReplay()
  historyReplayVisibleCount.value = historyReplaySteps.value.length
}

const pinHistoryToDashboard = async (entry) => {
  const restored = await openHistoricalAnalysisFromHistory(entry)
  if (restored === false) return
  await openPinDialog()
}

const disposeHistoryPreviewChart = () => {
  if (historyPreviewChartInstance) {
    historyPreviewChartInstance.dispose()
    historyPreviewChartInstance = null
  }
}

const renderHistoryPreviewChart = (entry) => {
  if (isHistoryTableEntry(entry) || !historyPreviewChartRef.value || !entry || !isHistoryEntryRestorable(entry)) {
    disposeHistoryPreviewChart()
    return
  }
  const seriesData = buildHistoryPreviewSeriesData(entry)
  if (!seriesData.length) {
    disposeHistoryPreviewChart()
    return
  }
  const chartType = String(entry?.chartType || '').trim() || 'bar'
  const metricLabel = String(entry?.fieldMapping?.metric || '指标').trim()
  if (!historyPreviewChartInstance) {
    historyPreviewChartInstance = echarts.init(historyPreviewChartRef.value)
  }
  const option = chartType === 'pie'
    ? {
        animation: false,
        tooltip: { trigger: 'item' },
        legend: { show: false },
        series: [{
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '54%'],
          label: {
            formatter: ({ name }) => {
              const text = String(name || '')
              return text.length > 6 ? `${text.slice(0, 6)}...` : text
            },
            color: '#334155',
            fontSize: 11
          },
          data: seriesData
        }]
      }
    : {
        animation: false,
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 52, right: 12, top: 20, bottom: 50 },
        xAxis: {
          type: 'category',
          data: seriesData.map(item => item.name),
          axisLine: { lineStyle: { color: '#cbd5e1' } },
          axisTick: { show: false },
          axisLabel: {
            color: '#64748b',
            fontSize: 10,
            interval: 0,
            rotate: seriesData.length > 4 ? 24 : 0,
            formatter: (value) => {
              const text = String(value || '')
              return text.length > 6 ? `${text.slice(0, 6)}...` : text
            }
          }
        },
        yAxis: {
          type: 'value',
          name: metricLabel,
          nameTextStyle: { color: '#64748b', fontSize: 10, padding: [0, 0, 0, -8] },
          axisLabel: {
            color: '#64748b',
            fontSize: 10,
            formatter: (value) => {
              const num = Number(value)
              if (!Number.isFinite(num)) return value
              if (Math.abs(num) >= 10000) return `${(num / 10000).toFixed(1)}w`
              return `${num}`
            }
          },
          splitLine: { lineStyle: { color: '#eef2f7' } }
        },
        series: [{
          type: chartType === 'line' ? 'line' : 'bar',
          smooth: chartType === 'line',
          symbol: chartType === 'line' ? 'circle' : 'none',
          symbolSize: chartType === 'line' ? 6 : 0,
          data: seriesData.map(item => item.value),
          barMaxWidth: 22,
          itemStyle: {
            color: chartType === 'line' ? '#2563eb' : '#3b82f6',
            borderRadius: chartType === 'line' ? 0 : [4, 4, 0, 0]
          },
          lineStyle: {
            width: 2,
            color: '#2563eb'
          },
          areaStyle: chartType === 'line'
            ? { color: 'rgba(59, 130, 246, 0.10)' }
            : undefined
        }]
      }
  historyPreviewChartInstance.setOption(option, true)
  historyPreviewChartInstance.resize()
}

const selectHistoryEntry = async (entry) => {
  if (!entry?.id) return
  selectedHistoryId.value = String(entry.id)
  historyDetailLoading.value = true
  try {
    await ensureHistoryEntrySnapshot(entry)
  } finally {
    historyDetailLoading.value = false
  }
}

watch(recentChatQueries, (items) => {
  const normalizedRows = Array.isArray(items) ? items : []
  if (!normalizedRows.length) {
    selectedHistoryId.value = ''
    return
  }
  const exists = normalizedRows.some(item => String(item?.id || '') === String(selectedHistoryId.value || ''))
  if (!exists) {
    selectedHistoryId.value = String(normalizedRows[0]?.id || '')
  }
}, { immediate: true })

watch(selectedHistoryEntry, async (entry) => {
  if (!entry?.id) return
  if (String(entry?.snapshotStatus || '').trim() !== 'unknown') return
  historyDetailLoading.value = true
  try {
    await ensureHistoryEntrySnapshot(entry)
  } finally {
    historyDetailLoading.value = false
  }
})

watch(historyReplaySteps, (steps) => {
  clearHistoryReplayTimer()
  historyReplayVisibleCount.value = 0
  historyReplayPlaying.value = false
  if (steps.length) {
    startHistoryReplay()
  }
}, { immediate: true })

watch(historyDrawerVisible, (visible) => {
  if (!visible) {
    historyQuickDateRange.value = ''
    selectedHistoryId.value = ''
    historyDetailLoading.value = false
    pauseHistoryReplay()
    disposeHistoryPreviewChart()
  }
})

watch(selectedHistoryEntry, async (entry) => {
  await nextTick()
  renderHistoryPreviewChart(entry)
})

watch(historyDetailLoading, async (loadingState) => {
  if (loadingState) return
  await nextTick()
  renderHistoryPreviewChart(selectedHistoryEntry.value)
})

watch(historyDrawerVisible, async (visible) => {
  if (!visible) return
  await nextTick()
  renderHistoryPreviewChart(selectedHistoryEntry.value)
})

watch(recentChatQueryDateRange, (range) => {
  if (!Array.isArray(range) || range.length !== 2 || !range[0] || !range[1]) {
    historyQuickDateRange.value = ''
    return
  }
  const todayRange = buildHistoryQuickDateRange('today')
  const weekRange = buildHistoryQuickDateRange('week')
  const monthRange = buildHistoryQuickDateRange('month')
  if (range[0] === todayRange[0] && range[1] === todayRange[1]) {
    historyQuickDateRange.value = 'today'
    return
  }
  if (range[0] === weekRange[0] && range[1] === weekRange[1]) {
    historyQuickDateRange.value = 'week'
    return
  }
  if (range[0] === monthRange[0] && range[1] === monthRange[1]) {
    historyQuickDateRange.value = 'month'
    return
  }
  historyQuickDateRange.value = ''
}, { immediate: true })

const toggleChatContentMode = async () => {
  const nextMode = chatContentMode.value === 'messages' ? 'sessions' : 'messages'
  chatContentMode.value = nextMode
  if (nextMode === 'sessions') {
    await searchChatSessions()
  }
}

const createSessionAndOpen = async () => {
  await createChatSession()
  chatContentMode.value = 'messages'
}

const selectSessionFromManager = async (sessionId) => {
  await selectChatSession(sessionId)
  chatContentMode.value = 'messages'
}

const refreshSessionFromManager = async (session) => {
  if (!session?.id) return
  if (String(activeChatSessionId?.value || '') !== String(session.id)) {
    await selectChatSession(session.id)
  }
  await refreshActiveChatSessionSummary()
}

const deleteSessionFromManager = async (session) => {
  await deleteChatSession(session)
  if (!activeChatSessionId?.value) {
    chatContentMode.value = 'sessions'
  }
}

const formatGraphContextTitle = (item) => {
  if (typeof item === 'string') return item
  return String(item?.label || item?.name || item?.nodeKey || item?.sourceId || '上下文片段')
}

const formatGraphContextSource = (item) => {
  if (!item || typeof item === 'string') return ''
  const parts = [
    item.type || item.nodeType,
    item.tableName,
    item.sourceId
  ].filter(Boolean)
  return parts.join(' · ')
}

const formatGraphContextContent = (item) => {
  if (typeof item === 'string') return item
  const content = String(item?.content || item?.description || item?.summary || '').trim()
  if (content) return content
  const pairs = Object.entries(item || {})
    .filter(([key, value]) => !['label', 'name', 'nodeKey', 'sourceId', 'type', 'nodeType', 'tableName'].includes(key) && value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
  return pairs.join('；') || '暂无详细内容'
}

const formatSemanticEvidenceTitle = (item) => {
  const role = String(item?.role || '依据').trim()
  const label = String(item?.label || item?.field || '').trim()
  const field = String(item?.field || '').trim()
  if (label && field && label !== field) return `${role}：${label}（${field}）`
  return `${role}：${label || field || '已命中'}`
}

const formatSemanticEvidenceSource = (item) => {
  const parts = [
    item?.source,
    item?.fieldType
  ].map(value => String(value || '').trim()).filter(Boolean)
  return parts.join(' · ')
}

const formatSemanticEvidenceContent = (item) => {
  const reason = String(item?.reason || '').trim()
  const expression = String(item?.formula || item?.expression || '').trim()
  return [reason, expression ? `表达式：${expression}` : ''].filter(Boolean).join('；') || '本次查询命中该语义项'
}

const disposeSemanticKnowledgeGraph = () => {
  if (semanticKnowledgeGraphInstance) {
    semanticKnowledgeGraphInstance.dispose()
    semanticKnowledgeGraphInstance = null
  }
}

const semanticGraphCategoryConfig = [
  { name: '数据表', itemStyle: { color: '#2563eb', borderColor: '#1d4ed8' } },
  { name: '字段', itemStyle: { color: '#ecfeff', borderColor: '#0891b2' } },
  { name: '业务指标', itemStyle: { color: '#ecfdf5', borderColor: '#16a34a' } },
  { name: '语义依据', itemStyle: { color: '#f5f3ff', borderColor: '#7c3aed' } },
  { name: '其他节点', itemStyle: { color: '#f8fafc', borderColor: '#64748b' } }
]

const renderSemanticKnowledgeGraph = async () => {
  await nextTick()
  const graph = semanticKnowledgeGraphData.value
  const el = semanticKnowledgeGraphRef.value
  if (!el || !semanticKnowledgeGraphVisible.value) {
    disposeSemanticKnowledgeGraph()
    return
  }
  const existing = echarts.getInstanceByDom(el)
  if (!existing && semanticKnowledgeGraphInstance) {
    semanticKnowledgeGraphInstance.dispose()
    semanticKnowledgeGraphInstance = null
  }
  semanticKnowledgeGraphInstance = existing || echarts.init(el)
  semanticKnowledgeGraphInstance.setOption({
    animationDuration: 550,
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params) => {
        if (params.dataType === 'edge') {
          return `${params.data?.source || ''} → ${params.data?.target || ''}<br/>关系：${params.data?.label || '关联'}`
        }
        const data = params.data || {}
        const parts = [
          `<strong>${data.value || data.name || '节点'}</strong>`,
          data.nodeType ? `类型：${data.nodeType}` : '',
          data.content ? `说明：${data.content}` : ''
        ].filter(Boolean)
        return parts.join('<br/>')
      }
    },
    legend: {
      top: 4,
      left: 'center',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#64748b', fontSize: 11 },
      data: semanticGraphCategoryConfig.map(item => item.name)
    },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      top: 34,
      bottom: 18,
      left: 16,
      right: 16,
      categories: semanticGraphCategoryConfig,
      data: graph.nodes.map(node => ({
        ...node,
        label: {
          show: true,
          color: node.category === 0 ? '#ffffff' : '#0f172a',
          fontWeight: node.category === 0 || node.category === 2 ? 700 : 600,
          fontSize: node.category === 0 ? 12 : 11
        },
        itemStyle: {
          ...(semanticGraphCategoryConfig[node.category]?.itemStyle || {}),
          borderWidth: 2,
          shadowBlur: node.category === 0 ? 10 : 4,
          shadowColor: node.category === 0 ? 'rgba(37, 99, 235, 0.20)' : 'rgba(15, 23, 42, 0.08)'
        }
      })),
      links: graph.edges.map(edge => ({
        ...edge,
        lineStyle: { color: '#94a3b8', width: 1.2, opacity: 0.78, curveness: 0.08 }
      })),
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 7],
      edgeLabel: {
        show: true,
        formatter: (params) => params.data?.label || params.data?.value || '',
        color: '#475569',
        fontSize: 10,
        backgroundColor: 'rgba(255, 255, 255, 0.82)',
        borderRadius: 3,
        padding: [1, 3]
      },
      force: {
        repulsion: 260,
        gravity: 0.06,
        edgeLength: [92, 150],
        friction: 0.42
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 2.2, color: '#2563eb' }
      }
    }]
  }, true)
  semanticKnowledgeGraphInstance.resize()
}

const resizeSemanticKnowledgeGraph = () => {
  semanticKnowledgeGraphInstance?.resize?.()
}

watch(semanticKnowledgeGraphSignature, () => {
  renderSemanticKnowledgeGraph()
}, { flush: 'post' })

onBeforeUnmount(() => {
  clearHistoryReplayTimer()
  disposeHistoryPreviewChart()
  disposeSemanticKnowledgeGraph()
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', resizeSemanticKnowledgeGraph)
  }
  chatMutationObserver?.disconnect()
  chatResizeObserver?.disconnect()
  if (typeof window !== 'undefined' && typeof window.cancelAnimationFrame === 'function') {
    chatScrollFrameIds.forEach(frameId => window.cancelAnimationFrame(frameId))
  }
  chatScrollFrameIds.clear()
})
</script>
<style scoped>
.chart-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 10px;
  justify-content: flex-end;
  flex: 0 0 auto;
  white-space: nowrap;
}
.chart-action-btn {
  min-width: 96px;
  height: 36px;
  padding: 0 14px;
  flex: 0 0 auto;
  border-radius: 7px;
  border-color: #dce4f0;
  background: #fff;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.chart-action-btn :deep(.el-icon) {
  margin-right: 5px;
  font-size: 15px;
}
.chart-action-btn--primary {
  border-color: #72a4ff;
  color: #3478f6;
  background: #f8fbff;
}
.chart-action-btn--primary:hover,
.chart-action-btn--primary:focus {
  border-color: #4f8dff;
  color: #2563eb;
  background: #f4f8ff;
}
.chat-panel .panel-header {
  align-items: center;
  margin-bottom: 10px;
}
.chat-panel .panel-header > div {
  min-width: 0;
}
.chat-panel .panel-header :deep(.el-button) {
  flex: 0 0 auto;
}
.chat-layout {
  display: grid;
  grid-template-columns: minmax(380px, 0.95fr) minmax(520px, 1.35fr);
  gap: 16px;
  min-height: 0;
  align-items: start;
}
.chat-panel {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.chart-panel {
  min-width: 0;
  min-height: 0;
  max-height: calc(142vh - 96px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chart-panel .panel-header {
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
}
.chart-panel-scroll {
  min-height: 0;
  flex: 1 1 auto;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 4px;
  padding-bottom: 12px;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.chart-panel-scroll::-webkit-scrollbar {
  width: 8px;
}
.chart-panel-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chart-panel-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #cbd5e1;
}
.chart-panel-scroll::-webkit-scrollbar-thumb:hover {
  background: #94a3b8;
}
.chart-panel-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1 1 auto;
}
.chart-panel-icon {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: #eef4ff;
  color: #2563eb;
}
.chart-panel-icon .el-icon {
  font-size: 18px;
}
.chart-panel-title-text {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.chart-panel-title-text h2 {
  margin: 0;
  color: #111827;
  font-size: 16px;
  line-height: 1.2;
  font-weight: 700;
}
.chart-panel-title-text p {
  margin: 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
  font-weight: 400;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chart-control-bar {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin: 16px 0 12px;
  padding: 12px;
  border: 1px solid #e5ebf5;
  border-radius: 8px;
  background: #fff;
}
.chart-control-item,
.chart-control-select {
  min-width: 0;
  width: 100%;
}
.chart-control-item {
  height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border: 1px solid #e5ebf5;
  border-radius: 7px;
  background: #fff;
  color: #344054;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.chart-control-item > .el-icon:first-child {
  flex: 0 0 auto;
  color: #3478f6;
  font-size: 18px;
}
.chart-control-item span,
.chart-control-item strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chart-control-item strong {
  color: #1f2937;
  font-weight: 700;
}
.chart-control-chevron {
  margin-left: auto;
  color: #98a2b3;
  font-size: 12px;
  transform: rotate(90deg);
}
.chart-control-select :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 0 0 1px #e5ebf5 inset, 0 1px 2px rgba(15, 23, 42, 0.03);
}
.chart-control-select :deep(.el-select__prefix) {
  gap: 8px;
  color: #3478f6;
}
.chart-control-select :deep(.el-select__prefix .el-icon) {
  font-size: 18px;
}
.chart-control-select-label {
  color: #344054;
  font-size: 14px;
  font-weight: 500;
}
.chart-control-select :deep(.el-select__selected-item) {
  color: #1f2937;
  font-size: 14px;
  font-weight: 500;
}
.chart-type-selected-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.chart-type-option {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.chart-type-recommend-pill {
  flex: 0 0 auto;
  padding: 2px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f3f4f6;
  color: #9ca3af;
  font-size: 12px;
  line-height: 18px;
  font-weight: 600;
}
.chart-type-recommend-pill--current {
  padding: 1px 7px;
  font-size: 11px;
  line-height: 16px;
}
.chart-empty-state {
  min-height: 520px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
  padding: 38px 24px 34px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #ffffff;
  text-align: center;
}
.chart-empty-illustration {
  width: min(360px, 78%);
  height: auto;
  display: block;
  margin: 38px auto 14px;
}
.chart-empty-state h3 {
  margin: 0;
  color: #303133;
  font-size: 18px;
  line-height: 26px;
  font-weight: 700;
}
.chart-empty-state p {
  max-width: 420px;
  margin: 8px 0 0;
  color: #909399;
  font-size: 14px;
  line-height: 22px;
}
.chart-empty-guide {
  width: min(680px, 100%);
  margin-top: 42px;
  padding: 14px 16px 16px;
  border: 1px solid rgba(226, 232, 240, 0.58);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.62);
  box-shadow: none;
  text-align: left;
}
.chart-empty-guide-title {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #1f2937;
  font-size: 14px;
  line-height: 20px;
  font-weight: 700;
}
.chart-empty-guide-title .el-icon {
  color: #3b82f6;
  font-size: 17px;
}
.chart-empty-guide-steps {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}
.chart-empty-guide-step {
  min-height: 62px;
  display: grid;
  grid-template-columns: 32px 34px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid rgba(226, 232, 240, 0.72);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}
.chart-empty-guide-index {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #edf4ff;
  color: #2563eb;
  font-size: 14px;
  font-weight: 700;
}
.chart-empty-guide-icon {
  color: #3b82f6;
  font-size: 24px;
  line-height: 1;
}
.chart-empty-guide-icon--database svg {
  width: 24px;
  height: 24px;
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.chart-empty-guide-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.chart-empty-guide-copy strong {
  color: #111827;
  font-size: 14px;
  line-height: 20px;
  font-weight: 700;
}
.chart-empty-guide-copy small {
  color: #8a94a6;
  font-size: 12px;
  line-height: 18px;
}
.chart-canvas {
  width: 100%;
  height: 360px;
  min-height: 360px;
  margin-top: 4px;
}
.analysis-metric-card {
  min-height: 360px;
  margin-top: 4px;
  padding: 28px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  text-align: center;
  box-sizing: border-box;
}
.analysis-metric-main {
  display: grid;
  gap: 10px;
  justify-items: center;
}
.analysis-metric-value {
  display: inline-flex;
  align-items: flex-end;
  justify-content: center;
  gap: 8px;
  color: #0f172a;
  font-size: 64px;
  line-height: 1;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  word-break: break-word;
}
.analysis-metric-value small {
  padding-bottom: 8px;
  color: #475569;
  font-size: 18px;
  line-height: 1;
  font-weight: 700;
}
.analysis-metric-label {
  max-width: min(520px, 100%);
  color: #475569;
  font-size: 16px;
  line-height: 1.5;
  font-weight: 600;
}
.analysis-metric-compare {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.4;
  flex-wrap: wrap;
}
.analysis-metric-trend {
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}
.analysis-metric-trend--up {
  color: #047857;
  background: #ecfdf5;
}
.analysis-metric-trend--down {
  color: #b91c1c;
  background: #fef2f2;
}
.analysis-metric-trend--flat {
  color: #475569;
  background: #f1f5f9;
}
.analysis-metric-note {
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.4;
}
@media (max-width: 640px) {
  .analysis-metric-card {
    padding: 20px;
  }
  .analysis-metric-value {
    font-size: 44px;
  }
}
.pin-dialog-hint {
  margin: 0 0 12px;
  color: #909399;
  font-size: 13px;
  line-height: 1.5;
}
.chat-datasource-bar {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.chat-filter-grid {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 32px;
}
.chat-filter-field {
  min-width: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 16px;
}
.chat-filter-field:first-child {
  flex: 1.08 1 0;
}
.chat-filter-field:last-child {
  flex: 0.92 1 0;
}
.chat-datasource-label {
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #1f2937;
  font-size: 15px;
  line-height: 1;
  font-weight: 700;
  white-space: nowrap;
}
.chat-datasource-label .el-icon {
  color: #2f7df6;
  font-size: 18px;
}
.chat-datasource-database-icon {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #2f7df6;
}
.chat-datasource-database-icon svg {
  width: 20px;
  height: 20px;
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.chat-toolbar-select {
  width: 100%;
}
.chat-toolbar-select :deep(.el-select__wrapper) {
  min-height: 34px;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 0 0 1px #e3e9f2 inset;
  padding: 0 10px;
}
.chat-toolbar-select :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px #d4dfec inset;
}
.chat-toolbar-select :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #a7c6ff inset;
}
.chat-toolbar-select :deep(.el-select__placeholder),
.chat-toolbar-select :deep(.el-select__selected-item) {
  color: #667085;
  font-size: 13px;
}
.chat-toolbar-select :deep(.el-select__selected-item),
.chat-toolbar-select :deep(.el-select__placeholder) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-selection-hint {
  display: none;
}
.chat-thread-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 0;
  padding: 12px 14px 10px;
  border: 1px solid #e6ebf2;
  border-bottom: 0;
  border-radius: 18px 18px 0 0;
  background: #fff;
}
.chat-thread-toggle {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #dbe3f0;
  border-radius: 8px;
  background: #f8fafc;
  color: #475467;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease, color 0.2s ease;
}
.chat-thread-toggle:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}
.chat-thread-title-wrap {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}
.chat-thread-title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-thread-subtitle {
  color: #64748b;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-thread-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}
.chat-thread-history-btn {
  border-radius: 8px;
}
.chat-thread-new-btn {
  border-radius: 8px;
}
.chat-thread-header--inside {
  margin: 0;
  padding: 12px 14px 8px;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.chat-conversation-shell {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e6ebf2;
  border-radius: 18px;
  background: #fff;
  box-shadow: inset 0 -1px 0 rgba(148, 163, 184, 0.08);
}
.chat-conversation-main {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0 14px 0;
  background: #fff;
}
.chat-conversation-shell .message-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 10px 2px 12px;
}
.chat-conversation-shell .avatar {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 50%;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}
.chat-conversation-shell .avatar img,
.chat-conversation-shell .avatar span {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.chat-conversation-shell .avatar img {
  width: 44px;
  height: 44px;
  object-fit: contain;
}
.chat-conversation-shell .message-wrapper.user .avatar {
  width: 64px;
  height: 64px;
  flex-basis: 64px;
  margin: -10px 12px 0 -2px;
}
.chat-conversation-shell .message-wrapper.user .avatar img {
  width: 64px;
  height: 64px;
}
.chat-session-manager {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  padding: 4px 14px 14px;
  border: 1px solid #e6ebf2;
  border-top: 0;
  border-radius: 0 0 18px 18px;
  background: #fff;
}
.chat-session-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px;
  gap: 6px;
  align-items: center;
  padding-top: 0;
}
.chat-session-status {
  width: 110px;
}
.chat-session-manager-list {
  min-height: 0;
  overflow-y: auto;
  display: grid;
  gap: 8px;
  padding-right: 2px;
}
.chat-session-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: stretch;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  padding: 10px;
}
.chat-session-card.active {
  border-color: #3b82f6;
  background: #eff6ff;
}
.chat-session-card-main {
  min-width: 0;
  border: 0;
  padding: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  display: grid;
  gap: 6px;
}
.chat-session-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chat-session-card-title {
  min-width: 0;
  color: #1f2937;
  font-size: 14px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-session-card-meta {
  color: #64748b;
  font-size: 12px;
}
.chat-session-card-summary {
  color: #475467;
  font-size: 12px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.chat-session-card-actions {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.chat-session-card-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #dbe3f0;
  border-radius: 8px;
  background: #fff;
  color: #64748b;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease, color 0.2s ease;
}
.chat-session-card-icon:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}
.chat-session-card-icon.danger:hover {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #dc2626;
}
.ask-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: 10px 12px 12px;
  background: transparent;
  box-shadow: none;
}
.message-followup-ref {
  display: grid;
  gap: 6px;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid #dbe7f6;
  border-radius: 10px;
  background: linear-gradient(180deg, #f8fbff 0%, #f3f7fc 100%);
}
.message-followup-ref__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
}
.message-followup-ref__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}
.message-followup-ref__content {
  color: #0f172a;
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}
.message-followup-ref__meta {
  color: #64748b;
  font-size: 11px;
  line-height: 1.4;
}
.chat-followup-banner {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 8px 0 10px;
  padding: 12px 14px;
  border: 1px solid #dbe7f6;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff 0%, #f2f7ff 100%);
}
.chat-followup-banner__main {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.chat-followup-banner__eyebrow {
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
}
.chat-followup-banner__title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.5;
  word-break: break-word;
}
.chat-followup-banner__meta {
  color: #64748b;
  font-size: 11px;
  line-height: 1.4;
  word-break: break-word;
}
.ask-bar :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.ask-bar :deep(.el-input__wrapper) {
  min-height: 44px;
  padding: 5px 7px 5px 16px;
  border-radius: 999px;
  background: #f7f9fc;
  box-shadow: inset 0 0 0 1px #e5eaf1;
}
.ask-bar :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: inset 0 0 0 1px #c7d7fe, 0 0 0 3px rgba(59, 130, 246, 0.08);
}
.ask-bar :deep(.el-input__inner) {
  height: 32px;
  color: #111827;
  font-size: 14px;
}
.ask-bar :deep(.el-input__prefix) {
  margin-right: 5px;
}
.ask-bar :deep(.el-input__suffix) {
  margin-left: 8px;
}
.ask-input-loading {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  display: inline-block;
  position: relative;
  animation: askLoadingSpin 0.9s linear infinite;
  opacity: 0.72;
}
.ask-input-loading i {
  position: absolute;
  left: 8px;
  top: 1px;
  width: 2px;
  height: 5px;
  border-radius: 999px;
  background: #aeb9c6;
  transform-origin: 1px 8px;
}
.ask-input-loading i:nth-child(1) { transform: rotate(0deg); opacity: 1; }
.ask-input-loading i:nth-child(2) { transform: rotate(45deg); opacity: 0.86; }
.ask-input-loading i:nth-child(3) { transform: rotate(90deg); opacity: 0.72; }
.ask-input-loading i:nth-child(4) { transform: rotate(135deg); opacity: 0.58; }
.ask-input-loading i:nth-child(5) { transform: rotate(180deg); opacity: 0.44; }
.ask-input-loading i:nth-child(6) { transform: rotate(225deg); opacity: 0.34; }
.ask-input-loading i:nth-child(7) { transform: rotate(270deg); opacity: 0.26; }
.ask-input-loading i:nth-child(8) { transform: rotate(315deg); opacity: 0.18; }
.ask-input-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 32px;
}
.chat-model-trigger {
  width: auto;
  min-width: 68px;
  max-width: 92px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 0 5px 0 7px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: #334155;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  box-shadow: none;
  outline: none;
  transition: background-color 0.18s ease, border-color 0.18s ease, color 0.18s ease;
}
.chat-model-trigger:hover {
  background: #f4f7fb;
  color: #1d4ed8;
  box-shadow: none;
}
.chat-model-trigger.active,
.chat-model-trigger:focus {
  background: #eef6ff;
  border-color: #b8d6ff;
  color: #1d4ed8;
  box-shadow: none;
}
.chat-model-trigger span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-model-trigger .el-icon {
  flex: 0 0 auto;
  font-size: 11px;
}
.ask-input-actions :deep(.chat-model-menu-popper.el-popover.el-popper) {
  padding: 8px;
  border: 1px solid #dde6f3;
  border-radius: 14px;
  background: #f8fafc;
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.16);
}
.chat-model-menu {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chat-model-menu__group {
  padding: 4px 10px 6px;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 700;
}
.chat-model-menu__item {
  width: 100%;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #1f2937;
  font-size: 14px;
  font-weight: 650;
  text-align: left;
  cursor: pointer;
}
.chat-model-menu__item:hover {
  background: #eef6ff;
  color: #1d4ed8;
}
.chat-model-menu__item.active {
  background: #e8f1ff;
  color: #1d4ed8;
}
.chat-model-menu__check {
  color: #2563eb;
  font-size: 15px;
}
.ask-input-icon-btn,
.ask-input-send-btn,
.ask-input-stop-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  padding: 0;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease, transform 0.18s ease;
}
.ask-input-icon-btn:hover,
.ask-input-icon-btn.active {
  background: #eef2f7;
  color: #2563eb;
}
.ask-input-icon-btn:disabled {
  cursor: not-allowed;
  color: #cbd5e1;
  background: transparent;
}
.ask-input-send-btn {
  width: 30px;
  height: 30px;
  background: #2563eb;
  color: #fff;
}
.ask-input-send-btn:hover {
  background: #1d4ed8;
  transform: translateY(-1px);
}
.ask-input-send-btn .el-icon {
  font-size: 16px;
}
.ask-input-stop-btn {
  width: 32px;
  height: 32px;
}
.ask-input-stop-btn:hover .stop-btn__disc {
  background: #d7dce5;
}
.stop-btn__disc {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #e5e7eb;
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.08);
}
.stop-btn__square {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: #111827;
}
@keyframes askLoadingSpin {
  to {
    transform: rotate(360deg);
  }
}
.bubble--thinking {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.thinking-spinner {
  position: relative;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  animation: thinkingSpinnerRotate 0.9s linear infinite;
}
.thinking-spinner i {
  position: absolute;
  left: 9px;
  top: 1px;
  width: 2px;
  height: 6px;
  border-radius: 999px;
  background: #8ea1b6;
  transform-origin: 1px 9px;
}
.thinking-spinner i:nth-child(1) { transform: rotate(0deg); opacity: 1; }
.thinking-spinner i:nth-child(2) { transform: rotate(45deg); opacity: 0.86; }
.thinking-spinner i:nth-child(3) { transform: rotate(90deg); opacity: 0.72; }
.thinking-spinner i:nth-child(4) { transform: rotate(135deg); opacity: 0.58; }
.thinking-spinner i:nth-child(5) { transform: rotate(180deg); opacity: 0.44; }
.thinking-spinner i:nth-child(6) { transform: rotate(225deg); opacity: 0.34; }
.thinking-spinner i:nth-child(7) { transform: rotate(270deg); opacity: 0.26; }
.thinking-spinner i:nth-child(8) { transform: rotate(315deg); opacity: 0.18; }
@keyframes thinkingSpinnerRotate {
  to {
    transform: rotate(360deg);
  }
}
.bubble-voice-action {
  display: flex;
  justify-content: flex-start;
  margin-top: 6px;
}
.bubble-voice-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease;
}
.bubble-voice-icon {
  font-size: 14px;
  line-height: 1;
}
.bubble-voice-graphic {
  font-size: 13px;
  line-height: 1;
}
.bubble-voice-btn:hover {
  background: #eef2ff;
  color: #1d4ed8;
}
.bubble-voice-btn:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: 2px;
}
.voice-status-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.voice-status-text {
  color: #64748b;
  font-size: 12px;
}
.voice-transcript-preview {
  color: #334155;
  font-size: 12px;
  line-height: 1.4;
}
.voice-error-tip {
  margin-top: 6px;
  color: #dc2626;
  font-size: 12px;
}
.voice-settings {
  display: grid;
  gap: 16px;
}
.voice-preview-box {
  min-height: 84px;
  width: 100%;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
  color: #334155;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.voice-history {
  display: grid;
  gap: 8px;
}
.voice-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}
.voice-history__list {
  display: grid;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
}
.voice-history__item {
  display: grid;
  gap: 4px;
  text-align: left;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  padding: 8px 10px;
  cursor: pointer;
}
.voice-history__item:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}
.voice-history__text {
  color: #0f172a;
  font-size: 12px;
  line-height: 1.5;
}
.voice-history__meta {
  color: #64748b;
  font-size: 11px;
}
.voice-history__empty {
  color: #9ca3af;
  font-size: 12px;
}
.diagnosis-preview-card {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding: 14px;
  border: 1px solid #dbe7f6;
  border-radius: 10px;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.diagnosis-preview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}
.diagnosis-preview-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}
.diagnosis-preview-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1d4ed8;
}
.diagnosis-preview-card.is-pending .diagnosis-preview-icon {
  border-color: #d8e1ee;
  background: #f8fafc;
  color: #475467;
}
.diagnosis-preview-title-main {
  min-width: 0;
}
.diagnosis-preview-kicker {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.3;
}
.diagnosis-preview-title-main h3 {
  margin: 3px 0 0;
  color: #0f172a;
  font-size: 16px;
  font-weight: 800;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.diagnosis-preview-action {
  flex: 0 0 auto;
  border-radius: 8px;
}
.diagnosis-preview-body {
  display: grid;
  gap: 10px;
}
.diagnosis-preview-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.diagnosis-preview-meta-item {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  padding: 4px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f8fafc;
  color: #475467;
  font-size: 12px;
  line-height: 1.3;
}
.diagnosis-preview-summary {
  display: grid;
  gap: 5px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.diagnosis-preview-card.is-pending .diagnosis-preview-summary {
  border-style: dashed;
  background: #ffffff;
}
.diagnosis-preview-summary span {
  color: #1f2a44;
  font-size: 12px;
  font-weight: 700;
}
.diagnosis-preview-summary p {
  max-height: 72px;
  margin: 0;
  overflow: auto;
  color: #334155;
  font-size: 13px;
  line-height: 1.65;
  white-space: normal;
  word-break: break-word;
}
.diagnosis-preview-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.diagnosis-preview-stat {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.diagnosis-preview-stat :deep(.el-icon) {
  flex: 0 0 auto;
  color: #2563eb;
}
.diagnosis-preview-stat div {
  min-width: 0;
  display: grid;
  gap: 1px;
}
.diagnosis-preview-stat span {
  color: #64748b;
  font-size: 11px;
}
.diagnosis-preview-stat strong {
  color: #0f172a;
  font-size: 15px;
  line-height: 1.2;
}
.graph-context-panel {
  grid-column: 1 / -1;
}
.semantic-evidence-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.semantic-evidence-help {
  color: #64748b;
  cursor: help;
  font-size: 16px;
  line-height: 1;
}
.semantic-evidence-help:hover {
  color: #1f2a44;
}
.graph-context-collapse {
  border: none;
}
.graph-context-collapse :deep(.el-collapse-item__header) {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0 12px;
  background: #f8fafc;
  color: #1f2a44;
  font-weight: 700;
}
.graph-context-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.graph-context-collapse :deep(.el-collapse-item__content) {
  padding: 12px 0 0;
}
.graph-context-collapse-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.graph-context-collapse-title small {
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
}
.semantic-evidence-hit-note {
  margin: 0 0 10px;
  padding: 9px 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1e3a8a;
  font-size: 13px;
  line-height: 1.55;
}
.semantic-evidence-hit-list {
  margin-bottom: 12px;
}
.semantic-evidence-hit-card {
  position: relative;
  overflow: hidden;
  border-color: #dbeafe;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.04);
}
.semantic-evidence-hit-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: #2563eb;
}
.semantic-evidence-hit-card .graph-context-meta,
.semantic-evidence-hit-card .graph-context-content {
  padding-left: 4px;
}
.semantic-evidence-hit-card .graph-context-name {
  color: #0f172a;
}
.semantic-evidence-hit-card .graph-context-sub {
  padding: 2px 7px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
  font-size: 11px;
}
.semantic-kg-card {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
}
.semantic-kg-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.semantic-kg-card__head div {
  min-width: 0;
  display: grid;
  gap: 2px;
}
.semantic-kg-card__head strong {
  color: #0f172a;
  font-size: 14px;
}
.semantic-kg-card__head span,
.semantic-kg-card__head small {
  color: #64748b;
  font-size: 12px;
}
.semantic-kg-card__head small {
  flex: 0 0 auto;
  padding-top: 1px;
}
.semantic-kg-chart {
  width: 100%;
  height: 320px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background:
    radial-gradient(circle at 50% 45%, rgba(37, 99, 235, 0.08), transparent 42%),
    #ffffff;
}
.graph-context-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 10px;
}
.graph-context-item {
  min-width: 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.graph-context-item--muted {
  background: #ffffff;
  border-style: dashed;
}
.graph-context-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.graph-context-name {
  color: #1f2a44;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.graph-context-sub {
  flex: 0 1 auto;
  color: #64748b;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.graph-context-content {
  color: #334155;
  line-height: 1.6;
  white-space: normal;
  word-break: break-word;
}
.semantic-evidence-empty {
  padding: 8px 0 2px;
}
:global(.history-product-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 16px 18px 6px;
}
:global(.history-product-drawer .el-drawer__title) {
  color: #111827;
  font-weight: 800;
}
:global(.history-product-drawer .el-drawer__body) {
  padding: 0 18px 20px;
}
.history-drawer {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 12px;
  padding-top: 0;
}
.history-toolbar {
  display: grid;
  gap: 10px;
  padding: 12px 14px 14px;
  border: 1px solid #e1e8f2;
  border-radius: 8px;
  background: #fbfdff;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.03);
}
.history-toolbar__primary {
  display: grid;
  grid-template-columns: minmax(260px, 1.5fr) repeat(4, minmax(150px, 1fr));
  gap: 12px;
  align-items: center;
}
.history-toolbar__secondary {
  display: grid;
  grid-template-columns: 390px 88px 88px 88px minmax(24px, 1fr) 126px 92px 82px;
  grid-template-rows: 36px 36px;
  align-items: center;
  gap: 10px 14px;
}
.history-toolbar :deep(.el-input),
.history-toolbar :deep(.el-select),
.history-toolbar :deep(.el-date-editor) {
  width: 100%;
}
.history-toolbar :deep(.el-input__wrapper),
.history-toolbar :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 7px;
  box-shadow: 0 0 0 1px #dce5f0 inset;
  background: #fff;
  color: #172033;
}
.history-toolbar :deep(.el-range-editor.el-input__wrapper) {
  min-height: 36px;
  width: 390px;
  max-width: 100%;
  padding-right: 10px;
}
.history-toolbar :deep(.el-input__wrapper:hover),
.history-toolbar :deep(.el-select__wrapper:hover),
.history-toolbar :deep(.el-range-editor.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b6c7dd inset;
}
.history-toolbar :deep(.el-input__inner),
.history-toolbar :deep(.el-select__placeholder),
.history-toolbar :deep(.el-range-input),
.history-toolbar :deep(.el-range-separator) {
  color: #6b7588;
  font-size: 13px;
  font-weight: 500;
}
.history-toolbar :deep(.el-input__prefix),
.history-toolbar :deep(.el-select__caret),
.history-toolbar :deep(.el-range__icon) {
  color: #8a96a8;
}
.history-toolbar__keyword,
.history-toolbar__table,
.history-toolbar__chart,
.history-toolbar__risk,
.history-toolbar__status,
.history-toolbar__date,
.history-toolbar__quick-range,
.history-toolbar__sort {
  min-width: 0;
}
.history-toolbar__date {
  grid-column: 1;
  grid-row: 1;
  width: 390px;
  max-width: 100%;
}
.history-toolbar__date :deep(.el-range-input) {
  flex: 1 1 128px;
  min-width: 98px;
}
.history-toolbar__date :deep(.el-range-separator) {
  flex: 0 0 34px;
  min-width: 34px;
  padding: 0;
  text-align: center;
}
.history-toolbar__quick-range {
  grid-column: 1 / span 4;
  grid-row: 2;
  display: grid;
  grid-template-columns: repeat(4, 88px);
  gap: 14px;
}
.history-toolbar__quick-range :deep(.el-button) {
  width: 100%;
  height: 34px;
  min-width: 0;
  padding: 0 12px;
  border: 1px solid #dfe7f2;
  border-radius: 7px;
  background: #fff;
  color: #172033;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.02);
}
.history-toolbar__quick-range :deep(.el-button + .el-button) {
  margin-left: 0;
}
.history-toolbar__quick-range :deep(.el-button.is-active) {
  border-color: #0b73ff;
  background: #0b73ff;
  color: #fff;
  box-shadow: 0 6px 14px rgba(11, 115, 255, 0.2);
}
.history-toolbar__sort {
  display: flex;
  justify-content: stretch;
  grid-column: 6;
  grid-row: 2;
}
.history-toolbar__sort :deep(.el-button) {
  width: 100%;
  min-height: 34px;
  border-radius: 7px;
  border-color: #dfe7f2;
  background: #fff;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  gap: 5px;
}
.history-toolbar__actions {
  display: grid;
  grid-template-columns: 92px 82px;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  grid-column: 7 / span 2;
  grid-row: 2;
}
.history-toolbar__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.history-toolbar__actions :deep(.el-button) {
  width: 100%;
  min-width: 0;
  min-height: 34px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 600;
}
.history-toolbar__actions :deep(.el-button--primary) {
  background: #0b73ff;
  border-color: #0b73ff;
  box-shadow: 0 6px 14px rgba(11, 115, 255, 0.2);
}
.history-toolbar__actions :deep(.el-button.is-plain) {
  background: #fff;
  border-color: #dfe7f2;
  color: #172033;
}
.history-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #64748b;
  font-size: 12px;
  padding: 0 2px;
}
.history-summary__error {
  color: #b91c1c;
}
.history-content {
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(320px, 0.95fr) minmax(0, 1.25fr);
  gap: 14px;
  overflow: hidden;
}
.history-list {
  min-height: 0;
  overflow-y: auto;
  display: grid;
  align-content: start;
  gap: 12px;
  padding-right: 4px;
}
.history-card {
  display: grid;
  gap: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  padding: 14px 14px 12px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background-color 0.2s ease;
}
.history-card:hover {
  border-color: #c8d7eb;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.07);
}
.history-card.is-active {
  border-color: #2563eb;
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.14), 0 10px 28px rgba(37, 99, 235, 0.08);
  background: linear-gradient(180deg, #f8fbff 0%, #f1f7ff 100%);
}
.history-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.history-card__title-wrap {
  min-width: 0;
  flex: 1;
}
.history-card__title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
  white-space: normal;
  word-break: break-word;
}
.history-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: #64748b;
  font-size: 12px;
}
.history-card__meta-item {
  min-width: 0;
  word-break: break-word;
  position: relative;
}
.history-card__meta-item:not(:last-child)::after {
  content: '';
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-left: 10px;
  border-radius: 999px;
  background: #cbd5e1;
  vertical-align: middle;
}
.history-card__status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.history-tag {
  border-radius: 999px;
  font-weight: 600;
  letter-spacing: 0;
}
.history-tag:deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
}
.history-tag--execution {
  border-color: rgba(59, 130, 246, 0.24);
}
.history-tag--risk {
  border-color: rgba(148, 163, 184, 0.24);
}
.history-tag--cache {
  border-color: rgba(99, 102, 241, 0.2);
}
.history-tag--snapshot {
  border-color: rgba(14, 165, 233, 0.2);
}
.history-tag--pinned {
  border-color: rgba(245, 158, 11, 0.24);
}
.history-detail {
  min-height: 0;
  overflow-y: auto;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  padding: 16px;
  display: grid;
  align-content: start;
  gap: 14px;
}
.history-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.history-detail__title-wrap {
  min-width: 0;
  display: grid;
  gap: 8px;
}
.history-detail__title {
  color: #0f172a;
  font-size: 18px;
  font-weight: 500;
  line-height: 1.45;
  word-break: break-word;
}
.history-detail__submeta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: #64748b;
  font-size: 12px;
}
.history-detail__tags {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
.history-detail__section {
  display: grid;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px solid #f1f5f9;
}
.history-detail__section-title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 500;
}
.history-detail__status-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.history-detail__status-item,
.history-detail__kv-item {
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.history-detail__status-label,
.history-detail__kv-label {
  color: #64748b;
  font-size: 12px;
}
.history-detail__status-value,
.history-detail__kv-value {
  color: #0f172a;
  font-size: 13px;
  font-weight: 400;
  word-break: break-word;
}
.history-detail__kv-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.history-detail__text,
.history-detail__placeholder,
.history-detail__hint {
  color: #475569;
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}
.audit-sensitive-list,
.history-detail__tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.analysis-meta {
  margin-top: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.analysis-meta :deep(.el-descriptions__body) {
  background: #fff;
}
.analysis-meta :deep(.el-descriptions__table) {
  table-layout: fixed;
}
.analysis-meta :deep(.el-descriptions__cell) {
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.5;
  vertical-align: middle;
}
.analysis-meta :deep(.el-descriptions__label) {
  width: 88px;
  min-width: 88px;
  color: #475569;
  font-weight: 700;
  background: #f8fafc;
  white-space: nowrap;
}
.analysis-meta :deep(.el-descriptions__content) {
  min-width: 0;
  color: #0f172a;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.analysis-meta :deep(.el-tag) {
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 20px;
}
.analysis-meta .audit-sensitive-list {
  gap: 6px;
}
.analysis-meta-long-text {
  display: -webkit-box;
  max-width: 100%;
  overflow: hidden;
  line-height: 1.5;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  white-space: normal;
}
.history-detail__tag-list {
  margin-top: 10px;
}
.history-detail__context-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.history-detail__reasoning-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 12px;
}
.history-detail__reasoning-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.history-detail__reasoning-progress {
  color: #64748b;
  font-size: 12px;
}
.history-detail__reasoning-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.history-detail__reasoning-item {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
.history-detail__reasoning-marker {
  position: relative;
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding-top: 12px;
}
.history-detail__reasoning-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12);
  position: relative;
  z-index: 1;
}
.history-detail__reasoning-line {
  position: absolute;
  top: 22px;
  bottom: -12px;
  width: 2px;
  background: #dbeafe;
  border-radius: 999px;
}
.history-detail__reasoning-item:last-child .history-detail__reasoning-line {
  display: none;
}
.history-detail__reasoning-card {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
}
.history-detail__reasoning-item.is-current .history-detail__reasoning-card {
  border-color: #93c5fd;
  background: #eff6ff;
}
.history-detail__reasoning-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.history-detail__reasoning-stepno {
  color: #2563eb;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0;
  white-space: nowrap;
}
.history-detail__reasoning-title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.5;
}
.history-detail__reasoning-detail {
  color: #475569;
  font-size: 12px;
  line-height: 1.7;
  word-break: break-word;
}
.history-detail__context-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 5px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1.4;
  word-break: break-word;
}
.history-detail__snapshot-preview {
  display: grid;
  gap: 10px;
}
.history-detail__thumbnail-card {
  display: grid;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #ffffff;
}
.history-detail__thumbnail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.history-detail__thumbnail-title {
  color: #0f172a;
  font-size: 12px;
  font-weight: 500;
}
.history-detail__thumbnail-meta {
  color: #64748b;
  font-size: 11px;
}
.history-detail__thumbnail-chart {
  width: 100%;
  height: 170px;
}
.history-detail__snapshot-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.history-detail__snapshot-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: linear-gradient(180deg, #f8fbff 0%, #f1f5f9 100%);
}
.history-detail__snapshot-card-head,
.history-detail__snapshot-card-metric,
.history-detail__snapshot-card-extra-item {
  display: grid;
  gap: 4px;
}
.history-detail__snapshot-card-label {
  color: #64748b;
  font-size: 11px;
  line-height: 1.4;
}
.history-detail__snapshot-card-title {
  color: #0f172a;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}
.history-detail__snapshot-card-value {
  color: #1d4ed8;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.3;
  word-break: break-word;
}
.history-detail__snapshot-card-extra {
  display: grid;
  gap: 8px;
  padding-top: 2px;
  border-top: 1px solid rgba(148, 163, 184, 0.24);
}
.history-detail__snapshot-card-extra-value {
  color: #334155;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
.history-detail__snapshot-table {
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  scrollbar-gutter: stable;
}
.history-detail__snapshot-table table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  table-layout: auto;
}
.history-detail__snapshot-table th,
.history-detail__snapshot-table td {
  padding: 9px 10px;
  border-bottom: 1px solid #e2e8f0;
  text-align: left;
  color: #334155;
  font-size: 12px;
  line-height: 1.5;
  min-width: 112px;
  max-width: 220px;
  white-space: normal;
  word-break: normal;
  overflow-wrap: anywhere;
  vertical-align: top;
}
.history-detail__snapshot-table th {
  background: #f8fafc;
  color: #0f172a;
  font-weight: 500;
  white-space: nowrap;
  overflow-wrap: normal;
  word-break: keep-all;
}
.analysis-table-wrap {
  min-height: 360px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.analysis-table-wrap :deep(.el-table) {
  --el-table-header-bg-color: #f8fafc;
}
.analysis-table-wrap :deep(.el-table th) {
  color: #0f172a;
  font-weight: 700;
}
.history-detail__sql-wrap {
  border-radius: 8px;
  overflow: hidden;
}
.history-detail__sql {
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.history-detail__footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 6px;
}
.history-detail__footer.is-readonly {
  opacity: 0.72;
}
.history-detail__action-hint {
  flex: 1 0 100%;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
  padding: 10px 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
}
.history-detail__footer :deep(.el-button + .el-button) {
  margin-left: 0;
}
.history-detail__footer :deep(.el-button) {
  min-width: 88px;
}
.history-detail__footer.is-readonly :deep(.el-button:not(.is-disabled)) {
  opacity: 0.86;
}
.history-detail__footer :deep(.el-button.is-disabled) {
  opacity: 0.42;
}
.history-empty-state {
  min-height: 320px;
  display: grid;
  place-items: center;
  gap: 8px;
  text-align: center;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  background: #fff;
  padding: 20px;
}
.history-empty-state__title {
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
}
.history-empty-state__text {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}
.history-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}
.thinking-details {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.field-binding-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #dbe7ff;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff 0%, #eef5ff 100%);
}
.field-binding-card__header {
  margin-bottom: 8px;
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 700;
}
.field-binding-card__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 20px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
}
.field-binding-card__item + .field-binding-card__item {
  margin-top: 8px;
}
.field-binding-card__label {
  min-width: 0;
  color: #1f2937;
  font-weight: 600;
  word-break: break-word;
}
.field-binding-card__arrow {
  color: #60a5fa;
  text-align: center;
  font-weight: 700;
}
.field-binding-card__field {
  min-width: 0;
  color: #0f766e;
  font-family: Consolas, 'Courier New', monospace;
  word-break: break-word;
}
.multi-step-card {
  width: min(680px, 100%);
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: linear-gradient(180deg, #f8fbff 0%, #eef6ff 100%);
  box-shadow: 0 10px 28px rgba(37, 99, 235, 0.08);
}
.multi-step-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.multi-step-card__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}
.multi-step-card__title {
  margin-top: 3px;
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
  word-break: break-word;
}
.multi-step-card__clarify {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
  color: #92400e;
  font-size: 12px;
  line-height: 1.5;
}
.multi-step-card__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.multi-step-card__summary span {
  padding: 4px 8px;
  border: 1px solid rgba(37, 99, 235, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.86);
  color: #475569;
  font-size: 12px;
  font-weight: 600;
}
.multi-step-card__steps {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}
.multi-step-card__step {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 9px;
  padding: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
}
.multi-step-card__step--completed {
  border-color: #bbf7d0;
}
.multi-step-card__step--pending {
  border-color: #fde68a;
}
.multi-step-card__step--failed {
  border-color: #fecaca;
}
.multi-step-card__step--skipped {
  border-color: #e5e7eb;
  background: rgba(248, 250, 252, 0.92);
}
.multi-step-card__step-index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: #e0f2fe;
  color: #0369a1;
  font-size: 12px;
  font-weight: 800;
}
.multi-step-card__step-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.multi-step-card__step-head strong {
  color: #0f172a;
  font-size: 13px;
}
.multi-step-card__confidence {
  color: #64748b;
  font-size: 12px;
}
.multi-step-card__question,
.multi-step-card__dependency,
.multi-step-card__message {
  margin-top: 5px;
  color: #475569;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
}
.multi-step-card__message {
  color: #334155;
}
.alert-draft-card {
  width: min(620px, 100%);
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fffaf3;
}
.alert-draft-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.alert-draft-card__eyebrow {
  color: #b45309;
  font-size: 12px;
  font-weight: 700;
}
.alert-draft-card__title {
  margin-top: 2px;
  color: #111827;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
  word-break: break-word;
}
.alert-draft-card__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 8px;
}
.alert-draft-card__grid div {
  min-width: 0;
  padding: 8px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #ffffff;
}
.alert-draft-card__grid span {
  display: block;
  color: #78716c;
  font-size: 11px;
  line-height: 1.4;
}
.alert-draft-card__grid strong {
  display: block;
  margin-top: 3px;
  color: #1f2937;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}
.alert-draft-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #fde68a;
}
.alert-draft-card__hint {
  min-width: 0;
  margin: 0;
  color: #92400e;
  font-size: 12px;
  line-height: 1.5;
}
.alert-draft-card__confirm {
  flex: 0 0 auto;
  height: 28px;
  padding: 0 12px;
  border-color: #f59e0b;
  background: #fffbeb;
  color: #92400e;
  font-weight: 700;
}
.alert-draft-card__confirm:hover,
.alert-draft-card__confirm:focus {
  border-color: #d97706;
  background: #fef3c7;
  color: #78350f;
}
.alert-draft-card__confirm:active {
  border-color: #b45309;
  background: #fde68a;
  color: #78350f;
}
.alert-draft-card__confirm.is-loading {
  color: #92400e;
}
.alert-event-table-card {
  width: min(620px, 100%);
  margin-top: 10px;
  padding: 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #f8fbff;
}
.alert-event-table-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.alert-event-table-card__eyebrow {
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}
.alert-event-table-card__title {
  margin-top: 2px;
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
}
.alert-event-table {
  border-radius: 8px;
  overflow: hidden;
}
.alert-event-table :deep(.el-table__header th) {
  background: #eff6ff;
  color: #1e3a8a;
  font-size: 12px;
  font-weight: 700;
}
.alert-event-table :deep(.el-table__cell) {
  padding: 7px 0;
}
.alert-event-table__rule {
  color: #0f172a;
  font-weight: 650;
  line-height: 1.45;
}
.alert-event-table__snapshot-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 650;
}
.alert-event-table-card__footer {
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}
.advanced-dialog-entry {
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
.advanced-dialog-entry__main {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.advanced-dialog-entry__type {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}
.advanced-dialog-entry__title {
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.4;
  word-break: break-word;
}
.advanced-dialog-entry__summary {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
.advanced-dialog-entry__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 2px;
}
.advanced-dialog-entry__rule {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 6px;
  margin-top: 6px;
}
.advanced-dialog-entry__rule div {
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.advanced-dialog-entry__rule span {
  display: block;
  color: #64748b;
  font-size: 11px;
  line-height: 1.4;
}
.advanced-dialog-entry__rule strong {
  display: block;
  margin-top: 2px;
  color: #0f172a;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-word;
}
.advanced-dialog-entry__rule-explain {
  grid-column: 1 / -1;
}
.advanced-analysis-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}
.advanced-analysis-dialog :deep(.advanced-card) {
  width: 100%;
  margin-top: 0;
  box-shadow: none;
}
.advanced-alert-rule-manager {
  display: grid;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
}
.advanced-alert-event-manager {
  display: grid;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
}
.advanced-alert-rule-manager__head,
.advanced-alert-rule-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.advanced-alert-rule-manager__head h4 {
  margin: 0;
  color: #0f172a;
  font-size: 14px;
}
.advanced-alert-rule-manager__head p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}
.advanced-alert-rule-list {
  display: grid;
  gap: 8px;
}
.advanced-alert-rule-item {
  align-items: flex-start;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}
.advanced-alert-event-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
}
.advanced-alert-rule-item__main {
  display: grid;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.advanced-alert-rule-item__main strong {
  color: #0f172a;
  font-size: 13px;
  line-height: 1.4;
  word-break: break-word;
}
.advanced-alert-rule-item__main span,
.advanced-alert-rule-empty {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}
.advanced-alert-rule-item__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 2px;
}
.forecast-confirm-form {
  display: grid;
  gap: 12px;
}
.whatif-confirm-dialog :deep(.el-dialog) {
  max-width: calc(100vw - 32px);
}
.whatif-confirm-dialog :deep(.el-dialog__body) {
  max-height: min(72vh, 720px);
  overflow-y: auto;
  padding-right: 22px;
}
.forecast-confirm-hint {
  margin-top: 6px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}
.forecast-confirm-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.whatif-variable-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.whatif-variable-toolbar__title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
}
.whatif-variable-list {
  display: grid;
  gap: 10px;
}
.whatif-variable-item {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}
.whatif-variable-item__main {
  display: grid;
  grid-template-columns: minmax(180px, 1.35fr) minmax(150px, 1fr) minmax(118px, 0.7fr) minmax(120px, 0.8fr);
  gap: 10px;
  align-items: end;
}
.whatif-variable-item__limits {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(120px, 1fr) auto;
  gap: 10px;
  align-items: end;
}
.whatif-variable-item__delete {
  min-width: 86px;
}
.whatif-variable-item :deep(.el-form-item) {
  margin-bottom: 0;
}
.whatif-variable-item :deep(.el-input-number) {
  width: 100%;
}
.whatif-variable-item :deep(.el-input-number .el-input__inner) {
  text-align: left;
}
.whatif-variable-item__main,
.whatif-variable-item__limits,
.whatif-variable-item :deep(.el-input),
.whatif-variable-item :deep(.el-select) {
  min-width: 0;
}
.forecast-confirm-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.thinking-details summary {
  cursor: pointer;
  color: #374151;
  font-size: 13px;
}
.chat-branch-banner {
  display: none;
}
.chat-branch-banner small {
  display: none;
}
.thinking-list {
  margin: 8px 0 0 18px;
  max-height: 140px;
  overflow: auto;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
}
.sql-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: #1f2937;
  border-bottom: 1px solid #374151;
}
.sql-head .sql-title {
  padding: 0;
  border: 0;
}

@media (max-width: 1100px) {
  .chat-layout {
    grid-template-columns: 1fr;
  }
  .chat-panel {
    min-width: 0;
  }
}

@media (max-width: 900px) {
  .history-toolbar__primary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .history-toolbar__keyword,
  .history-toolbar__table,
  .history-toolbar__chart,
  .history-toolbar__risk,
  .history-toolbar__status,
  .history-toolbar__date {
    grid-column: 1 / -1;
  }
  .history-toolbar__secondary {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
    align-items: stretch;
  }
  .history-toolbar__date,
  .history-toolbar__quick-range,
  .history-toolbar__sort,
  .history-toolbar__actions {
    grid-column: 1 / -1;
  }
  .history-toolbar__date,
  .history-toolbar :deep(.el-range-editor.el-input__wrapper) {
    width: 100%;
  }
  .history-toolbar__quick-range {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .history-toolbar__actions,
  .history-toolbar__sort {
    width: 100%;
  }
  .history-toolbar__sort :deep(.el-button) {
    width: 100%;
  }
  .history-toolbar__actions {
    justify-content: stretch;
  }
  .history-toolbar__actions :deep(.el-button) {
    flex: 1;
  }
  .history-content {
    grid-template-columns: 1fr;
  }
  .history-detail__snapshot-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .history-detail__head {
    flex-direction: column;
  }
  .history-detail__tags {
    justify-content: flex-start;
  }
  .history-detail__thumbnail-chart {
    height: 200px;
  }
  .chat-session-toolbar {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .chat-session-toolbar :deep(.el-input) {
    grid-column: 1 / -1;
  }
  .chat-session-status {
    width: 100%;
  }
  .chat-session-card {
    grid-template-columns: 1fr;
  }
  .chat-session-card-actions {
    justify-content: flex-end;
  }
  .chat-thread-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .chat-thread-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .analysis-meta {
    border-radius: 8px;
  }
  .analysis-meta :deep(.el-descriptions__table) {
    display: block;
  }
  .analysis-meta :deep(.el-descriptions__table tbody),
  .analysis-meta :deep(.el-descriptions__table tr),
  .analysis-meta :deep(.el-descriptions__table th),
  .analysis-meta :deep(.el-descriptions__table td) {
    display: block;
    width: 100% !important;
  }
  .analysis-meta :deep(.el-descriptions__label) {
    border-right: 0;
    border-bottom: 1px solid #edf2f7;
  }
}

@media (max-width: 720px) {
  .advanced-dialog-entry {
    align-items: stretch;
    grid-template-columns: 1fr;
  }
  .multi-step-card__header {
    flex-direction: column;
    align-items: stretch;
  }
  .multi-step-card__step {
    grid-template-columns: 22px minmax(0, 1fr);
    padding: 9px;
  }
  .multi-step-card__step-index {
    width: 22px;
    height: 22px;
  }
  .alert-draft-card__footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .alert-draft-card__confirm {
    width: 100%;
  }
  .advanced-dialog-entry__rule {
    grid-template-columns: 1fr;
  }
  .forecast-confirm-grid {
    grid-template-columns: 1fr;
  }
  .whatif-variable-item__main,
  .whatif-variable-item__limits {
    grid-template-columns: 1fr;
  }
  .whatif-variable-item__delete {
    width: 100%;
  }
  .chat-followup-banner {
    flex-direction: column;
    align-items: stretch;
  }
  .diagnosis-preview-head {
    flex-direction: column;
  }
  .diagnosis-preview-action {
    width: 100%;
  }
  .diagnosis-preview-title-main h3 {
    white-space: normal;
  }
  .diagnosis-preview-stats {
    grid-template-columns: 1fr;
  }
  .semantic-kg-card__head {
    flex-direction: column;
  }
  .semantic-kg-card__head small {
    padding-top: 0;
  }
  .semantic-kg-chart {
    height: 260px;
  }
  .history-toolbar {
    padding: 10px;
  }
  .history-toolbar__primary {
    grid-template-columns: 1fr;
  }
  .history-toolbar__quick-range {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .history-toolbar__actions {
    grid-template-columns: 1fr 1fr;
  }
  .history-toolbar__actions :deep(.el-button) {
    flex: 1;
  }
  .history-summary {
    flex-wrap: wrap;
    gap: 8px;
  }
  .history-detail__status-grid,
  .history-detail__kv-grid {
    grid-template-columns: 1fr;
  }
  .history-detail__snapshot-cards {
    grid-template-columns: 1fr;
  }
  .history-pagination {
    justify-content: center;
  }
}
</style>
