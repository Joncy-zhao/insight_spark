<template>
<section class="chat-layout">
          <div class="panel chat-panel">
            <div class="panel-header">
              <div>
                <h2>✨ 智能问答助理</h2>
                <p>随时随地，像对话一样探索您的业务数据及图表</p>
              </div>
              <el-button size="small" plain @click="businessDictionaryPanelVisible = true">
                业务字典/公式维护
              </el-button>
            </div>

            <div class="chat-datasource-bar">
              <div class="chat-filter-grid">
                <div class="chat-filter-field">
                  <div class="chat-datasource-label">数据源</div>
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
                  <div class="chat-datasource-label">业务模型</div>
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

            <div class="chat-thread-header">
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
                <div class="chat-thread-title">{{ chatContentMode === 'messages' ? currentChatSessionTitle : '连续对话' }}</div>
                <div class="chat-thread-subtitle">
                  {{ chatContentMode === 'messages' ? currentChatSessionSubtitle : '查看、切换和管理已有对话' }}
                </div>
              </div>
              <div class="chat-thread-actions">
                <el-button size="small" plain type="primary" class="chat-thread-history-btn" @click="openAdvancedAnalysisManagePage">
                  预测与情景模拟
                </el-button>
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
                  <el-button size="small" text type="primary" :loading="chatSessionLoading" @click="createSessionAndOpen">
                    新建
                  </el-button>
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
                  <div v-if="!chatSessions?.value?.length" class="chat-session-manager-empty">
                    <div class="chat-session-manager-empty__title">暂无会话</div>
                    <div class="chat-session-manager-empty__text">发送问题后会自动创建，或现在新建一个空会话。</div>
                    <el-button size="small" type="primary" @click="createSessionAndOpen">新建会话</el-button>
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div v-if="activeBranchParentTurnMeta" class="chat-branch-banner">
                <span>当前将基于指定消息继续追问</span>
                <small>{{ activeBranchParentTurnMeta.preview || `Turn #${activeBranchParentTurnMeta.turnNo || activeBranchParentTurnMeta.turnId}` }}</small>
                <el-button size="small" text @click="clearActiveBranchParent">清除</el-button>
              </div>

              <div class="message-list" id="chatHistory">
                <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
                  <div class="avatar">{{ msg.role === 'system' ? '🤖' : '👤' }}</div>
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
                    <div class="bubble">{{ msg.content }}</div>
                    <div v-if="msg.advancedAnalysis" class="advanced-dialog-entry">
                      <div class="advanced-dialog-entry__main">
                        <div class="advanced-dialog-entry__type">{{ advancedAnalysisTypeLabel(msg.advancedAnalysis.type) }}</div>
                        <div class="advanced-dialog-entry__title">{{ msg.advancedAnalysis.title }}</div>
                        <div class="advanced-dialog-entry__summary">{{ msg.advancedAnalysis.summary }}</div>
                      </div>
                      <el-tag size="small" effect="light" :type="msg.advancedAnalysis.status === '模拟生成' ? 'warning' : 'success'">
                        {{ msg.advancedAnalysis.status || '已生成' }}
                      </el-tag>
                      <el-button size="small" type="primary" plain @click="openAdvancedAnalysisDialog(msg.advancedAnalysis)">
                        查看详情
                      </el-button>
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
                          {{
                            item.targetType === 'metricDefinition'
                              ? (item.formula || item.fieldDisplayName || fieldLabel(item.field) || item.field || '已更新')
                              : (item.fieldDisplayName || fieldLabel(item.field) || item.field || '未绑定成功')
                          }}
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
                          v-if="msg.clickableChart"
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

              <div class="ask-bar">
                <el-input
                    v-model="question"
                    placeholder="试试问我：按省份统计销售额、按日期看趋势、分类占比等..."
                    :disabled="loading"
                    @keyup.enter="sendChatQuestion"
                >
                  <template #append>
                    <el-button type="primary" :loading="loading" @click="sendChatQuestion">
                      <span v-if="!loading">🚀 发送分析</span>
                      <span v-else>思考中...</span>
                    </el-button>
                  </template>
                </el-input>
                <el-button
                    class="voice-btn"
                    :type="listening ? 'danger' : 'primary'"
                    plain
                    :disabled="loading || !recognitionSupported"
                    :title="listening ? '停止语音输入' : '开始语音输入'"
                    :aria-label="listening ? '停止语音输入' : '开始语音输入'"
                    @click="listening ? stopVoiceQuestionInput() : startVoiceQuestionInput()"
                >
                  <el-icon><Microphone /></el-icon>
                </el-button>
                <el-button
                    class="voice-btn"
                    plain
                    title="语音设置"
                    aria-label="语音设置"
                    @click="voicePanelVisible = true"
                >
                  <el-icon><Setting /></el-icon>
                </el-button>
                <el-button v-if="loading" type="danger" plain class="stop-btn" @click="stopQuestionGeneration">
                  停止生成
                </el-button>
              </div>
            </template>
          </div>

          <div class="panel chart-panel">
            <div class="panel-header">
              <div>
                <h2>📊 智能可视化呈现</h2>
                <p>AI 将理解您的意图并推荐最合适的 ECharts 图表类型</p>
              </div>
              <div class="chart-actions">
                <el-tag v-if="currentChartType" type="success" effect="dark" round>
                  {{ chartTypeLabel }}效果
                </el-tag>
                <el-button
                    v-if="canRegenerateLastAnalysis"
                    size="small"
                    type="primary"
                    plain
                    :disabled="loading || isStreaming"
                    @click="regenerateLastAnalysis"
                >
                  重新生成
                </el-button>
                <el-select v-model="chartSortMode" size="small" style="width: 150px;" @change="() => lastAnalysis?.data?.length && renderChart(lastAnalysis.data, currentChartType)">
                  <el-option label="按数值降序" value="desc" />
                  <el-option label="按数值升序" value="asc" />
                  <el-option label="按名称排序" value="name" />
                </el-select>
                <el-button v-if="lastAnalysis?.data?.length" size="small" @click="exportChartAsImage">导出图片</el-button>
                <el-button
                    v-if="canPinLastAnalysis"
                    size="small"
                    type="success"
                    plain
                    :disabled="loading || isStreaming"
                    @click="openPinDialog"
                >
                  钉入看板
                </el-button>
                <el-button
                    v-if="canDiagnoseLastAnalysis"
                    type="warning"
                    :loading="diagnosisLoading"
                    @click="diagnoseFromLastAnalysis"
                >
                  一键生成诊断报告
                </el-button>
              </div>
            </div>

            <div id="echarts-container" class="chart-canvas"></div>

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
              <el-descriptions-item v-if="lastAnalysis.chartRuleName || lastAnalysis.chartRuleCode" label="命中规则">
                <el-tag type="primary" size="small">{{ lastAnalysis.chartRuleName || lastAnalysis.chartRuleCode }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRuleCode" label="规则编码">
                {{ lastAnalysis.chartRuleCode }}
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartScenarioType" label="推荐场景">
                {{ lastAnalysis.chartScenarioType }}
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRecommendationStatus" label="推荐状态">
                {{ lastAnalysis.chartRecommendationStatus }}
              </el-descriptions-item>
              <el-descriptions-item v-if="lastAnalysis.chartRecommendationExplain" label="推荐说明" :span="2">
                {{ lastAnalysis.chartRecommendationExplain }}
              </el-descriptions-item>
            </el-descriptions>

            <el-card v-if="currentDiagnosis" class="diagnosis-preview-card" shadow="hover" style="margin: 18px 0 0 0;">
              <template #header>
                <div style="display: flex; align-items: center; gap: 8px;">
                  <el-icon><i class="el-icon-document"></i></el-icon>
                  <span>最新诊断报告预览</span>
                </div>
              </template>
              <div>
                <div style="font-weight: bold; font-size: 16px; margin-bottom: 4px;">{{ currentDiagnosis.title || '智能诊断报告' }}</div>
                <div style="color: #888; font-size: 13px; margin-bottom: 8px;">生成时间：{{ currentDiagnosis.createdAt ? currentDiagnosis.createdAt.slice(0, 19).replace('T', ' ') : '' }}</div>
                <div style="margin-bottom: 8px;">摘要：{{ currentDiagnosis.summary || '暂无摘要' }}</div>
                <el-button size="small" type="primary" @click="activeModule = 'diagnosis'">查看完整报告</el-button>
              </div>
            </el-card>

          </div>
          <div v-if="lastAnalysis?.graphContext?.length" class="panel graph-context-panel">
            <div class="panel-header">
              <div>
                <h2>GraphRAG 上下文</h2>
                <p>展示本次问答召回的数据表、字段、公式和知识片段。</p>
              </div>
            </div>
            <div class="graph-context-list">
              <div
                  v-for="(item, index) in lastAnalysis.graphContext"
                  :key="item.nodeKey || item.sourceId || index"
                  class="graph-context-item"
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
          </div>
          <el-dialog v-model="pinDialogVisible" title="钉入我的看板" width="520px">
            <el-form label-position="top">
              <el-form-item label="目标看板">
                <el-select v-model="pinDashboardId" class="full-width" placeholder="请选择看板">
                  <el-option
                      v-for="dashboard in dashboardOptions"
                      :key="dashboard.id"
                      :label="dashboard.name + (dashboard.isPublic ? '（公开）' : '')"
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
              size="74%"
              destroy-on-close
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
                    <el-option label="表格" value="table" />
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
                </div>
                <div class="history-toolbar__secondary">
                  <div class="history-toolbar__quick-range">
                    <el-segmented
                        v-model="historyQuickDateRange"
                        :options="historyQuickDateOptions"
                        @change="applyHistoryQuickDateRange"
                    />
                  </div>
                  <div class="history-toolbar__sort">
                    <el-button plain @click="toggleHistorySortDirection">
                      {{ recentChatQuerySortDirection === 'ASC' ? '时间正序' : '时间倒序' }}
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
                      <span class="history-card__meta-item">{{ formatHistoryExecutionTime(entry.executionTimeMs) }}</span>
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
                          <span class="history-detail__status-value">{{ formatHistoryExecutionTime(selectedHistoryEntry.executionTimeMs) }}</span>
                        </div>
                        <div class="history-detail__status-item">
                          <span class="history-detail__status-label">图表行数</span>
                          <span class="history-detail__status-value">{{ selectedHistoryEntry.chartDataCount || 0 }}</span>
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
                          <table>
                            <thead>
                            <tr>
                              <th v-for="column in historySnapshotPreviewColumns(selectedHistoryEntry)" :key="column">
                                {{ column }}
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
                                  :key="`${selectedHistoryEntry.id}-${rowIndex}-${column}`"
                              >
                                {{ formatHistoryValue(row[column]) }}
                              </td>
                            </tr>
                            </tbody>
                          </table>
                        </div>
                        <div class="history-detail__thumbnail-card">
                          <div class="history-detail__thumbnail-head">
                            <div class="history-detail__thumbnail-title">图表缩略图</div>
                            <div class="history-detail__thumbnail-meta">
                              {{ selectedHistoryEntry.fieldMapping?.dimension || '维度' }} / {{ selectedHistoryEntry.fieldMapping?.metric || '指标' }}
                            </div>
                          </div>
                          <div ref="historyPreviewChartRef" class="history-detail__thumbnail-chart"></div>
                        </div>
                      </div>
                      <div v-else class="history-detail__placeholder">
                        暂无可预览的图表快照
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
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowLeftBold, ArrowRightBold, Close, Edit, Management, Microphone, Refresh, Search, Setting, Share, View } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import BusinessDictionaryView from '../../components/BusinessDictionaryView.vue'
import AdvancedAnalysisCard from '../../components/AdvancedAnalysisCard.vue'
import {
  explainAdvancedAnalysisResult,
  fetchAdvancedAnalysisFieldMeta,
  deleteAdvancedAlertRule,
  getAdvancedAlertRule,
  listAdvancedAlertEvents,
  listAdvancedAlertRules,
  saveAdvancedAnalysisPlan,
  saveAdvancedAlertRule,
  parseAdvancedAnalysisIntent,
  runAdvancedForecast,
  runAdvancedForecastFromSeries,
  runAdvancedAlertDetection,
  runAdvancedWhatIf,
  updateAdvancedAlertRule,
  updateAdvancedAlertRuleStatus
} from '../../api/advancedAnalysis'

const localVoiceGenderOptions = [
  { label: '男声', value: 'male' },
  { label: '女声', value: 'female' }
]

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
  chartSortMode,
  chatDom,
  createDatasource,
  currentChartType,
  currentDiagnosis,
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
  loadFields,
  loadPermissionCenter,
  loadPreview,
  loadSchemaTables,
  loadTables,
  loading,
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
  selectedChatBusinessModelId,
  chatBusinessModelOptions,
  businessModels,
  sendQuestion,
  regenerateLastAnalysis,
  openPinDialog,
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
  uploadFile,
  uploadResult,
  uploading,
  userQuestion,
  xAxisData
} = inject('workbench')

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

const currentChatSession = computed(() =>
  (chatSessions?.value || []).find(item => String(item?.id || '') === String(activeChatSessionId?.value || '')) || null
)

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

const advancedAnalysisTypeLabel = (type) => {
  if (type === 'forecast') return '时序预测'
  if (type === 'whatIf') return 'What-if 推演'
  if (type === 'alert') return '离线智能预警'
  return '高级分析'
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
  if (/预测|预估|未来|走势|forecast|prophet|holt/.test(content)) return 'forecast'
  if (/what-?if|如果|若|假设|提升|下降|降低|增长|推演|模拟|利润变化/.test(content)) return 'whatIf'
  if (/预警|提醒|告警|低于|高于|超过|异常|阈值|通知|钉钉|邮件|z-?score/.test(content)) return 'alert'
  return ''
}

const inferMetricFromQuestion = (text) => {
  const content = String(text || '')
  const candidates = ['销售额', '利润', '成本', '销量', '收入', '转化率', '退货率', '客单价']
  return candidates.find(item => content.includes(item)) || String(lastAnalysis?.value?.fieldMapping?.metric || '').trim() || '核心指标'
}

const inferForecastHorizon = (text) => {
  const content = String(text || '')
  if (/6\s*个?月|半年/.test(content)) return '6m'
  if (/3\s*个?月|季度/.test(content)) return '3m'
  if (/30\s*天|一个月|1\s*个?月/.test(content)) return '30d'
  if (/7\s*天|一周|1\s*周/.test(content)) return '7d'
  return '3m'
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
  const horizon = String(params.horizon || '3m')
  const futureCount = horizon === '7d' ? 7 : horizon === '30d' ? 8 : horizon === '6m' ? 6 : 3
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

const normalizeHorizonCount = (horizon) => {
  const value = String(horizon || '').trim()
  if (value === '7d') return 7
  if (value === '30d') return 8
  if (value === '6m') return 6
  return 3
}

const parseChartNumber = (value) => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const parsed = Number(String(value ?? '').replace(/,/g, '').trim())
  return Number.isFinite(parsed) ? parsed : null
}

const getRowValueByCandidates = (row, candidates = []) => {
  if (!row || typeof row !== 'object') return undefined
  for (const key of candidates) {
    if (key && Object.prototype.hasOwnProperty.call(row, key)) {
      return row[key]
    }
  }
  return undefined
}

const resolveLastAnalysisTimeSeries = () => {
  const analysis = lastAnalysis?.value
  const rows = Array.isArray(analysis?.data) ? analysis.data : []
  if (rows.length < 3) return []
  const mapping = analysis?.fieldMapping || {}
  const dimensionKey = String(mapping.dimensionKey || '').trim()
  const metricKey = String(mapping.metricKey || '').trim()
  const dimensionCandidates = [
    dimensionKey,
    'name',
    'dim_name',
    'dimension',
    'bucket_name',
    'date',
    'month',
    'time'
  ].filter(Boolean)
  const metricCandidates = [
    metricKey,
    'value',
    'metric_value',
    'metric',
    'sales_amt',
    'amount',
    'total'
  ].filter(Boolean)
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
  const futureCount = normalizeHorizonCount(params.horizon)
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

const confirmForecastParams = (fieldMeta, defaults = {}) => new Promise((resolve) => {
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
  forecastConfirmResolver = resolve
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

const confirmWhatIfParams = (fieldMeta, defaults = {}) => new Promise((resolve) => {
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
  whatIfConfirmResolver = resolve
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

const confirmAlertParams = (fieldMeta, defaults = {}) => new Promise((resolve) => {
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
  alertConfirmResolver = resolve
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
    timeRange: result?.granularity || params.horizon || '自定义周期',
    status: '真实计算',
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

const buildAnalysisFromSavedAlertRule = (rule, text, params, llmIntent, fieldMeta = {}) => {
  const metric = formatAnalysisMetricLabel(rule?.metricField || params.metricField || llmIntent?.metric || inferMetricFromQuestion(text), inferMetricFromQuestion(text), fieldMeta?.numericFields)
  const threshold = Number(rule?.threshold ?? params.threshold ?? inferAlertThreshold(text))
  const operator = rule?.operator || params.operator || 'lt'
  const series = buildAlertSeries(Number.isFinite(threshold) ? threshold : inferAlertThreshold(text))
  const abnormalCount = series.filter(item => operator === 'gt' ? item.value > threshold : operator === 'lt' ? item.value < threshold : Math.abs(item.zScore || 0) >= 3).length
  return {
    id: `advanced-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    type: 'alert',
    title: `${metric}预警规则`,
    summary: '预警规则已保存，后续离线 Agent 可按检测周期轮询数据并生成预警事件。',
    tableName: rule?.tableName || params.tableName || selectedTableName?.value || '',
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
      timeField: rule?.timeField || params.timeField || '',
      metricField: rule?.metricField || params.metricField || ''
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
      { label: '模拟异常', value: `${abnormalCount} 次` },
      { label: '通知渠道', value: formatAlertChannel(Array.isArray(rule?.channels) ? rule.channels : params.channels || []) }
    ]
  }
}

const createAdvancedAnalysisAsync = async (type, text, params = {}, llmIntent = {}) => {
  const tableName = selectedTableName?.value || lastAnalysis?.value?.tableName || ''
  if (type === 'whatIf') {
    assertWhatIfInstructionAllowed(text, params, llmIntent)
  }
  if (!tableName) {
    return createAdvancedAnalysis(type, text, params, llmIntent)
  }
  try {
    const fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName })
    if (type === 'forecast') {
      const mergedParams = {
        horizon: params.horizon || llmIntent.horizon || inferForecastHorizon(text),
        algorithm: params.algorithm || llmIntent.algorithm || 'Holt-Winters',
        confidence: params.confidence || llmIntent.confidence || '95%',
        alpha: params.alpha ?? 0.55,
        beta: params.beta ?? 0.28,
        gamma: params.gamma ?? 0.20,
        seasonLength: params.seasonLength ?? 0
      }
      const questionMetric = inferMetricFromQuestion(text)
      const timeField = String(llmIntent.timeField || lastAnalysis?.value?.fieldMapping?.dimensionKey || text || '').trim()
      const metricField = String(llmIntent.metricField || llmIntent.targetMetricField || lastAnalysis?.value?.fieldMapping?.metricKey || llmIntent.metric || questionMetric || text || '').trim()
      const inferredPayload = {
        tableName,
        timeField: pickFieldName(fieldMeta?.timeFields || [], timeField, ''),
        metricField: pickFieldName(fieldMeta?.numericFields || [], metricField, ''),
        filterExpression: params.filterExpression || llmIntent.filterExpression || '',
        granularity: llmIntent.granularity || 'month',
        horizon: normalizeHorizonCount(mergedParams.horizon),
        algorithm: mergedParams.algorithm,
        alpha: mergedParams.alpha,
        beta: mergedParams.beta,
        gamma: mergedParams.gamma,
        seasonLength: mergedParams.seasonLength
      }
      const confirmedPayload = await confirmForecastParams(fieldMeta, inferredPayload)
      if (!confirmedPayload) {
        throw new Error('已取消预测参数确认')
      }
      const chartSeries = resolveLastAnalysisTimeSeries()
      const hasFilterExpression = Boolean(String(confirmedPayload.filterExpression || '').trim())
      if (!hasFilterExpression && chartSeries.length >= 3) {
        const result = await runAdvancedForecastFromSeries({
          tableName,
          metric: llmIntent.metric || lastAnalysis?.value?.fieldMapping?.metric || inferMetricFromQuestion(text),
          series: chartSeries,
          horizon: normalizeHorizonCount(mergedParams.horizon),
          algorithm: mergedParams.algorithm,
          alpha: mergedParams.alpha,
          beta: mergedParams.beta,
          gamma: mergedParams.gamma,
          seasonLength: mergedParams.seasonLength
        })
        return buildAnalysisFromRealForecast(result, text, { ...mergedParams, sourceSeries: chartSeries }, llmIntent, fieldMeta)
      }
      const payload = {
        ...inferredPayload,
        ...confirmedPayload
      }
      if (!payload.timeField || !payload.metricField) {
        throw new Error('缺少可用于真实预测的时间字段或数值指标，且上一轮查询结果不足以预测')
      }
      const result = await runAdvancedForecast(payload)
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
      })
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
      })
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
      const metricField = String(llmIntent.metricField || llmIntent.targetMetricField || lastAnalysis?.value?.fieldMapping?.metricKey || llmIntent.metric || questionMetric || text || '').trim()
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
      })
      if (!confirmedAlert) {
        throw new Error('已取消预警规则确认')
      }
      const savedRule = await saveAdvancedAlertRule(confirmedAlert)
      return buildAnalysisFromSavedAlertRule(savedRule, text, confirmedAlert, llmIntent, fieldMeta)
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
    const mergedParams = {
      horizon: params.horizon || llmIntent.horizon || inferForecastHorizon(text),
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
      timeRange: mergedParams.horizon === '6m' ? '未来 6 个月' : mergedParams.horizon === '30d' ? '未来 30 天' : mergedParams.horizon === '7d' ? '未来 7 天' : '未来 3 个月',
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
  const series = buildAlertSeries(threshold)
  const abnormalCount = series.filter(item => operator === 'gt' ? item.value > threshold : item.value < threshold).length
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
      channel: params.channel || llmIntent.channel || 'both'
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
      { label: '模拟异常', value: `${abnormalCount} 次` },
      { label: '检测方式', value: operator === 'zscore' ? 'Z-Score' : '阈值检测' }
    ]
  }
}

const parseAdvancedIntentWithLlm = async (text) => {
  try {
    const tableName = selectedTableName?.value || lastAnalysis?.value?.tableName || ''
    let fieldMeta = null
    if (tableName) {
      try {
        fieldMeta = await fetchAdvancedAnalysisFieldMeta({ tableName })
      } catch (error) {
        console.warn('advanced analysis field meta unavailable:', error)
      }
    }
    const parsed = await parseAdvancedAnalysisIntent({
      question: text,
      tableName,
      context: {
        lastMetric: lastAnalysis?.value?.fieldMapping?.metric || '',
        lastMetricKey: lastAnalysis?.value?.fieldMapping?.metricKey || '',
        lastDimension: lastAnalysis?.value?.fieldMapping?.dimension || '',
        lastDimensionKey: lastAnalysis?.value?.fieldMapping?.dimensionKey || '',
        chartType: lastAnalysis?.value?.chartType || '',
        sourceQuestion: lastAnalysis?.value?.sourceQuestion || '',
        fields: fieldMeta?.fields || [],
        timeFields: fieldMeta?.timeFields || [],
        numericFields: fieldMeta?.numericFields || []
      }
    })
    const intent = normalizeAdvancedIntentType(parsed?.intent || parsed?.type)
    if (!intent) return null
    return { ...parsed, intent }
  } catch (error) {
    console.warn('advanced analysis llm parse fallback:', error)
    return null
  }
}

const pushAdvancedAnalysisMessage = (analysis, userText = '') => {
  messages.value.push({
    role: 'system',
    content: `${advancedAnalysisTypeLabel(analysis.type)}已生成，请在卡片中调整参数、重新计算或保存方案。`,
    advancedAnalysis: analysis,
    sourceQuestion: userText,
    sourceTableName: analysis.tableName || selectedTableName?.value || ''
  })
  nextTick(() => {
    const dom = document.getElementById('chatHistory')
    if (dom) dom.scrollTop = dom.scrollHeight
  })
}

const alertEventStatusLabel = (status) => {
  const value = String(status || 'OPEN').toUpperCase()
  if (value === 'ACK') return '已确认'
  if (value === 'CLOSED') return '已关闭'
  return '待处理'
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
    : [{ name: event.bucketName || '触发点', value: event.actualValue }]
  return rows.map(item => ({
    name: String(item?.name || item?.bucketName || '-'),
    value: Number(item?.value ?? item?.actualValue ?? 0),
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
  activeAdvancedAnalysis.value = analysis
  advancedAnalysisDialogVisible.value = true
}

const sendChatQuestion = async () => {
  const text = String(question?.value || '').trim()
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
  const llmIntent = await parseAdvancedIntentWithLlm(text)
  const intent = llmIntent?.intent || localIntent
  messages.value.push({
    role: 'user',
    content: text,
    parentTurnId: String(activeBranchParentTurnMeta?.value?.turnId || '').trim() || null
  })
  question.value = ''
  try {
    const analysis = await createAdvancedAnalysisAsync(intent, text, {}, llmIntent || {})
    pushAdvancedAnalysisMessage(analysis, text)
  } catch (error) {
    const message = error.message || '高级分析生成失败'
    ElMessage.error(message)
    messages.value.push({
      role: 'system',
      content: `生成预测与情景模拟卡片失败：${message}`,
      sourceTableName: selectedTableName?.value || lastAnalysis?.value?.tableName || ''
    })
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
    target.advancedAnalysis = nextAnalysis
  }
  if (activeAdvancedAnalysis.value?.id === analysis.id) {
    activeAdvancedAnalysis.value = nextAnalysis
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
        source: 'chat-analysis-card'
      }
    })
    const nextAnalysis = {
      ...analysis,
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
    ...analysis,
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

const pinAdvancedAnalysis = (analysis) => {
  saveAdvancedAnalysis(analysis)
  ElMessage.success('已生成可钉入看板的预测图表记录')
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
  const text = String(type || '').trim()
  if (text === 'bar') return '柱状图'
  if (text === 'line') return '折线图'
  if (text === 'pie') return '饼图'
  if (text === 'table') return '表格'
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

const historySnapshotStatusLabel = (entry) => {
  const status = String(entry?.snapshotStatus || '').trim()
  if (status === 'ready') return '可恢复'
  if (status === 'missing') return '缺失'
  if (status === 'error') return '异常'
  return entry?.hasChartSnapshot ? '可恢复' : '缺失'
}

const historySnapshotStatusType = (entry) => {
  const status = String(entry?.snapshotStatus || '').trim()
  if (status === 'ready') return 'primary'
  if (status === 'missing') return 'info'
  if (status === 'error') return 'danger'
  return entry?.hasChartSnapshot ? 'primary' : 'info'
}

const historyExecutionStatusLabel = (entry) => {
  const status = Number(entry?.executionStatus)
  if (status === 1) return '执行成功'
  if (status === 0) return '执行失败'
  if (status === 2) return '已取消'
  return '未知'
}

const historyExecutionStatusType = (entry) => {
  const status = Number(entry?.executionStatus)
  if (status === 1) return 'success'
  if (status === 0) return 'danger'
  if (status === 2) return 'warning'
  return 'info'
}

const historyCacheLabel = (entry) => entry?.isHitCache ? '命中缓存' : '未命中缓存'
const historyCacheTagType = (entry) => entry?.isHitCache ? 'primary' : 'info'
const isHistoryEntryRestorable = (entry) => Boolean(entry?.hasChartSnapshot)

const formatHistoryValue = (value) => {
  if (value == null || value === '') return '--'
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(item => formatHistoryValue(item)).join('、')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const formatHistoryExecutionTime = (value) => {
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
  const rows = Array.isArray(entry?.chartSnapshot?.data) ? entry.chartSnapshot.data : []
  return rows.slice(0, 3).map(row => (row && typeof row === 'object' ? row : { value: row }))
}

const historySnapshotPreviewColumns = (entry) =>
  Object.keys(historySnapshotPreviewRows(entry)[0] || {})

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
      titleLabel: titleKey || '维度',
      titleValue: titleKey ? formatHistoryValue(row?.[titleKey]) : `第${index + 1}条`,
      metricLabel: metricFieldKey || '指标',
      metricValue: metricFieldKey ? formatHistoryValue(row?.[metricFieldKey]) : '--',
      extraFields: extraFieldKeys.map(key => ({
        label: key,
        value: formatHistoryValue(row?.[key])
      }))
    }
  })
}

const historyRestoreHint = (entry) => {
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
  if (!historyPreviewChartRef.value || !entry || !isHistoryEntryRestorable(entry)) {
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

onBeforeUnmount(() => {
  clearHistoryReplayTimer()
  disposeHistoryPreviewChart()
})
</script>
<style scoped>
.chart-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
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
  min-height: 0;
}
.chat-panel {
  min-height: 0;
}
.chat-datasource-bar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}
.chat-filter-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
}
.chat-filter-field {
  min-width: 0;
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
}
.chat-datasource-label {
  margin: 0;
  color: #475467;
  font-size: 12px;
  line-height: 1.4;
  font-weight: 600;
}
.chat-toolbar-select {
  width: 100%;
}
.chat-toolbar-select :deep(.el-select__wrapper) {
  min-height: 30px;
}
.chat-toolbar-select :deep(.el-select__selected-item),
.chat-toolbar-select :deep(.el-select__placeholder) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-selection-hint {
  grid-column: 2;
  margin-top: -5px;
  color: #98a2b3;
  font-size: 11px;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-thread-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
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
.chat-session-manager {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
}
.chat-session-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px auto;
  gap: 6px;
  align-items: center;
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
.chat-session-manager-empty {
  min-height: 220px;
  display: grid;
  place-items: center;
  gap: 8px;
  text-align: center;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  background: #fff;
  padding: 20px;
}
.chat-session-manager-empty__title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
}
.chat-session-manager-empty__text {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}
.ask-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
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
  margin-top: 12px;
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
.ask-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.stop-btn {
  flex: 0 0 auto;
}
.voice-btn {
  flex: 0 0 auto;
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
}
.voice-btn :deep(.el-icon) {
  font-size: 13px;
}
.voice-btn :deep(.el-button__text),
.voice-btn :deep(.el-button__inner) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
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
.graph-context-panel {
  grid-column: 1 / -1;
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
.history-drawer {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: 14px;
}
.history-toolbar {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
}
.history-toolbar__primary {
  display: grid;
  grid-template-columns: minmax(240px, 1.5fr) repeat(3, minmax(120px, 0.8fr)) minmax(260px, 1.1fr);
  gap: 10px;
  align-items: center;
}
.history-toolbar__secondary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.history-toolbar :deep(.el-input),
.history-toolbar :deep(.el-select),
.history-toolbar :deep(.el-date-editor) {
  width: 100%;
}
.history-toolbar :deep(.el-input__wrapper),
.history-toolbar :deep(.el-select__wrapper) {
  min-height: 40px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #d8e1ee inset;
  background: #fff;
}
.history-toolbar :deep(.el-range-editor.el-input__wrapper) {
  min-height: 40px;
  padding-right: 10px;
}
.history-toolbar :deep(.el-input__wrapper:hover),
.history-toolbar :deep(.el-select__wrapper:hover),
.history-toolbar :deep(.el-range-editor.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b8c8df inset;
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
.history-toolbar__quick-range :deep(.el-segmented) {
  width: 100%;
  padding: 4px;
  border-radius: 10px;
  background: #eef3fb;
}
.history-toolbar__quick-range {
  flex: 1 1 280px;
  min-width: 0;
}
.history-toolbar__sort {
  display: flex;
  justify-content: flex-start;
}
.history-toolbar__sort :deep(.el-button) {
  min-width: 110px;
  border-radius: 10px;
  border-color: #d7e3f4;
  color: #334155;
}
.history-toolbar__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex: 0 0 auto;
  flex-wrap: wrap;
}
.history-toolbar__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.history-toolbar__actions :deep(.el-button) {
  min-width: 96px;
  border-radius: 10px;
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
  font-weight: 700;
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
  font-weight: 700;
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
  font-weight: 600;
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
  font-weight: 700;
  letter-spacing: 0;
  white-space: nowrap;
}
.history-detail__reasoning-title {
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
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
  font-weight: 700;
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
  font-weight: 700;
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
  overflow: auto;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}
.history-detail__snapshot-table table {
  width: 100%;
  min-width: 320px;
  border-collapse: collapse;
  table-layout: fixed;
}
.history-detail__snapshot-table th,
.history-detail__snapshot-table td {
  padding: 9px 10px;
  border-bottom: 1px solid #e2e8f0;
  text-align: left;
  color: #334155;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
  vertical-align: top;
}
.history-detail__snapshot-table th {
  background: #f8fafc;
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
.advanced-dialog-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
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
    flex-direction: column;
    align-items: stretch;
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
}

@media (max-width: 720px) {
  .advanced-dialog-entry {
    align-items: stretch;
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
  .history-toolbar {
    padding: 10px;
  }
  .history-toolbar__primary {
    grid-template-columns: 1fr;
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
