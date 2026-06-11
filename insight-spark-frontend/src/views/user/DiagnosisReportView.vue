<template>
<div class="diagnosis-layout">
  <div class="diagnosis-toolbar">
    <div class="toolbar-title">
      <div>
        <h1>智能诊断报告</h1>
        <p>基于 GraphRAG + Neo4j 的异常根因分析与报告生成</p>
      </div>
    </div>
    <div class="toolbar-actions">
      <el-button :loading="diagnosisLoading" :disabled="!currentDiagnosis" @click="runDiagnosisWithDetail">
        重新生成
      </el-button>
      <el-button :disabled="!canExportDiagnosis" @click="exportDiagnosisReportWithOptions('word')">
        <el-icon><Document /></el-icon>
        导出 Word
      </el-button>
      <el-button :disabled="!canExportDiagnosis" @click="exportDiagnosisReportWithOptions('pdf')">
        <el-icon><Document /></el-icon>
        导出 PDF
      </el-button>
      <el-button @click="resetDiagnosisWorkspace">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>
  </div>

  <div class="panel report-generator-panel">
    <div class="panel-header">
      <div>
        <h2>诊断配置</h2>
        <p>对对话查询、看板中的异常数据一键触发 GraphRAG 深度多跳推理，生成可追溯的根因分析报告。</p>
      </div>
      <el-tag type="info" effect="dark">GraphRAG + Neo4j</el-tag>
    </div>

    <div class="generator-form">
      <div class="form-block config-field field-detail-level">
        <label class="form-block-label">报告详细程度</label>
        <el-radio-group v-model="reportGenerateForm.detailLevel" class="report-detail-toggle">
          <el-radio label="simple">简易</el-radio>
          <el-radio label="detailed">详细</el-radio>
        </el-radio-group>
      </div>

      <div class="form-block config-field field-anomaly-type">
        <label class="form-block-label">异常类型</label>
        <el-select v-model="reportGenerateForm.anomalyType" class="full-width">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
      </div>

      <div class="form-block config-field field-data-table">
        <label class="form-block-label">诊断数据表</label>
        <el-select v-model="selectedTableName" placeholder="选择数据源" class="full-width" filterable>
          <el-option-group v-if="uploadTables?.length" label="上传数据表">
            <el-option v-for="table in uploadTables" :key="table.tableName" :label="table.displayName" :value="table.tableName" />
          </el-option-group>
          <el-option-group v-if="officialQueryTables?.length" label="官方授权库">
            <el-option v-for="table in officialQueryTables" :key="table.tableName" :label="table.displayName" :value="table.tableName" />
          </el-option-group>
        </el-select>
      </div>

      <div class="form-block config-field field-metric">
        <label class="form-block-label">指标字段</label>
        <el-select v-model="diagnosisForm.metricField" placeholder="选择数值指标" class="full-width">
          <el-option
            v-for="field in numericFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </div>

      <div class="form-block config-field field-dimension">
        <label class="form-block-label">维度字段</label>
        <el-select v-model="diagnosisForm.dimensionFields" multiple placeholder="选择拆解维度" class="full-width">
          <el-option
            v-for="field in dimensionCandidateFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </div>

      <div class="form-block config-field field-time">
        <label class="form-block-label">时间字段</label>
        <el-select v-model="diagnosisForm.timeField" placeholder="可选" clearable class="full-width">
          <el-option
            v-for="field in dateFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </div>

      <div class="form-block config-field field-export-options">
        <label class="form-block-label">导出内容配置</label>
        <div class="export-options">
          <el-checkbox v-model="exportOptions.includeSnapshots">包含图表快照</el-checkbox>
          <el-checkbox v-model="exportOptions.includeReasoningLogs">包含推理日志</el-checkbox>
        </div>
      </div>

      <div class="form-block config-field field-pdf-encryption">
        <label class="form-block-label">PDF 加密设置</label>
        <div class="pdf-encryption-row">
          <el-switch v-model="exportOptions.enablePdfEncryption" />
          <span>启用密码保护</span>
        </div>
      </div>

      <div class="form-block full-row-field">
        <label class="form-block-label">企业内部文档 / 行业研报</label>
        <div class="knowledge-upload-panel">
          <div class="knowledge-upload-row">
            <el-upload
              v-model:file-list="knowledgeDocUploadFiles"
              :auto-upload="false"
              :show-file-list="true"
              accept=".txt,.md,.pdf,.docx"
              :on-change="onKnowledgeDocChange"
              :on-remove="onKnowledgeDocRemove"
              multiple
            >
              <div class="knowledge-upload-dropzone" :class="{ 'is-uploading': knowledgeDocUploading }">
                <el-icon><UploadFilled /></el-icon>
                <span>{{ knowledgeDocUploading ? '正在上传并纳入 GraphRAG...' : '点击上传或拖拽文件到此处' }}</span>
                <small>支持 TXT、Markdown、PDF、Word（≤50MB）</small>
              </div>
            </el-upload>
          </div>
          <div class="knowledge-file-note">
            <span>{{ knowledgeDocStatusText }}</span>
          </div>
          <div v-if="knowledgeDocs?.length" class="knowledge-doc-list">
            <div v-for="doc in knowledgeDocs.slice(0, 4)" :key="doc.id" class="knowledge-doc-item">
              <div>
                <span>{{ doc.title || doc.fileName }}</span>
                <small>{{ doc.docType || 'DOC' }} · {{ doc.chunkCount || 0 }} 个切片</small>
              </div>
              <el-button text size="small" type="danger" @click="deleteKnowledgeDoc(doc)">删除</el-button>
            </div>
          </div>
        </div>
      </div>

    </div>

  </div>

  <div class="panel diagnosis-result">
    <div class="panel-header">
      <div>
        <h2>报告内容预览</h2>
        <p>包含深度分析文本、数据波动值、异常关联因素、根因定位结论与改进建议，并支持图表快照与异常节点标注。</p>
      </div>
    </div>

    <div v-if="!currentDiagnosis" class="empty-report-state">
      <div class="empty-report-visual" aria-hidden="true">
        <div class="report-illustration">
          <span class="illustration-line line-short"></span>
          <span class="illustration-line"></span>
          <span class="illustration-line line-mid"></span>
          <div class="illustration-bars">
            <i></i>
            <i></i>
            <i></i>
          </div>
          <div class="illustration-chart"></div>
        </div>
      </div>
      <div class="empty-report-copy">
        <h3>尚未生成诊断报告</h3>
        <p>生成后将包含以下内容：</p>
        <div class="empty-feature-grid">
          <span v-for="item in emptyReportFeatures" :key="item">
            <el-icon><Check /></el-icon>
            {{ item }}
          </span>
        </div>
        <el-button type="primary" class="empty-generate-button" :loading="diagnosisLoading" :disabled="!diagnosisForm.metricField" @click="runDiagnosisWithDetail">
          生成诊断报告
        </el-button>
        <small>预计耗时：2-5 分钟</small>
      </div>
    </div>
    <div v-else class="report-content-body">
      <el-alert
        v-if="!isReportPersisted"
        type="warning"
        :closable="false"
        show-icon
        :title="persistWarningTitle"
        style="margin-bottom: 10px;"
      />
      <el-alert
        v-if="graphRagRuntimeMode === 'FALLBACK_DIAGNOSIS'"
        type="warning"
        :closable="false"
        show-icon
        :title="graphRagRuntimeWarning"
        style="margin-bottom: 10px;"
      />
      <el-alert class="report-success-alert" type="success" :closable="false" show-icon :title="businessSummaryText(currentDiagnosis)" />

      <section class="report-section">
        <div class="section-heading">
          <div>
            <h3>报告概览</h3>
            <p>核心指标、诊断摘要与关键异常节点。</p>
          </div>
        </div>
        <div class="metric-card-grid">
          <div v-for="card in overviewMetricCards" :key="card.label" class="metric-card" :class="`is-${card.tone}`">
            <div>
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
            </div>
            <el-icon class="metric-card-icon">
              <component :is="card.icon" />
            </el-icon>
          </div>
        </div>
        <div class="overview-grid">
          <div class="summary-box">
            <strong>诊断摘要</strong>
            <p>{{ diagnosisSummaryText }}</p>
          </div>
          <div class="summary-box">
            <strong>关键异常节点</strong>
            <el-table :data="anomalyNodeRows" size="small" border empty-text="暂无异常节点">
              <el-table-column prop="time" label="日期/窗口" min-width="130" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="valueLabel" label="指标值" width="130" />
              <el-table-column prop="type" label="类型" min-width="160" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="reason" label="说明" min-width="140" :show-overflow-tooltip="reportTooltipConfig" />
            </el-table>
          </div>
        </div>
      </section>

      <section class="report-section">
        <div class="root-cause-grid">
          <div class="summary-box root-cause-box">
            <strong>根因定位</strong>
            <span class="box-caption">根因结论、业务维度拆解与改进建议。</span>
            <el-table
              class="root-cause-table"
              :data="currentDiagnosis.rootCauses || []"
              size="small"
              border
              empty-text="暂无根因假设"
            >
              <el-table-column type="expand" width="42">
                <template #default="{ row }">
                  <div class="root-cause-evidence-row">
                    <span>证据说明</span>
                    <p>{{ displayEvidenceText(row.evidence) }}</p>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="level" label="等级" width="96">
                <template #default="{ row }">
                  <el-tag size="small" :type="rootCauseLevelType(row.level)" effect="light">
                    {{ row.level || 'MEDIUM' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="causeType" label="类型" min-width="180" />
              <el-table-column prop="impactField" label="影响对象" min-width="120" />
              <el-table-column prop="confidence" label="置信度" width="130">
                <template #default="{ row }">
                  <div class="root-cause-confidence-cell">
                    <span>{{ formatConfidence(row.confidence) }}</span>
                    <el-progress :percentage="confidencePercent(row.confidence)" :show-text="false" />
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="recommendation-box">
            <strong>改进建议</strong>
            <ul class="suggestion-list">
              <li v-for="suggestion in currentDiagnosis.suggestions || []" :key="suggestion">{{ suggestion }}</li>
            </ul>
          </div>
        </div>
      </section>

      <section class="report-section dimension-section">
        <div class="section-heading">
          <div>
            <h3>口径明细</h3>
          </div>
        </div>
        <el-table class="dimension-table" :data="dimensionBreakdownRows" size="small" border empty-text="暂无维度拆解">
          <el-table-column prop="scope" label="口径" width="130" />
          <el-table-column prop="dimension" label="业务维度" min-width="140" />
          <el-table-column prop="factor" label="首要因子" min-width="160" :show-overflow-tooltip="reportTooltipConfig" />
          <el-table-column prop="valueLabel" label="贡献值" width="150" />
          <el-table-column prop="ratioLabel" label="占比" width="110" />
        </el-table>
      </section>

      <section class="report-section report-detail-launcher">
        <div class="section-heading">
          <div>
            <h3>详情查看</h3>
          </div>
        </div>
        <div class="detail-action-grid">
          <el-button v-for="item in reportDetailEntries" :key="item.type" @click="openDetailDialog(item.type)">
            {{ item.label }}
          </el-button>
        </div>
      </section>

      <el-dialog v-model="detailDialogVisible" :title="detailDialogTitle" width="86%" top="5vh" class="report-detail-dialog">
        <div v-if="detailDialogType === 'evidence'" class="tab-section dialog-tab-section">
            <h3>企业文档 / 行业研报证据</h3>
            <el-table :data="currentDiagnosis.docEvidence || []" border empty-text="暂无文档证据">
              <el-table-column prop="label" label="文档" min-width="160" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="sourceType" label="类型" width="110" />
              <el-table-column prop="content" label="命中片段" min-width="300" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="score" label="相关度" width="90" />
            </el-table>
            <h3>异常关联因素梳理</h3>
            <el-table :data="currentDiagnosis.relatedKnowledge || []" border empty-text="暂无关联知识">
              <el-table-column prop="nodeType" label="类型" width="140" />
              <el-table-column prop="label" label="名称" min-width="160" />
              <el-table-column prop="sourceType" label="来源" width="100" />
              <el-table-column prop="content" label="说明" min-width="260" :show-overflow-tooltip="reportTooltipConfig" />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'reasoning'" class="tab-section dialog-tab-section">
            <el-alert v-if="currentDiagnosis.graphReasoningPath" type="info" :closable="false" :title="currentDiagnosis.graphReasoningPath" />
            <el-table :data="currentDiagnosis.graphRagEvidenceChain || []" border empty-text="暂无 GraphRAG 证据链">
              <el-table-column prop="step" label="跳数" width="70" />
              <el-table-column prop="hopType" label="推理环节" width="150" />
              <el-table-column prop="label" label="命中对象" min-width="160" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="detail" label="证据说明" min-width="320" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="confidence" label="置信度" width="90" />
            </el-table>
            <el-timeline class="reasoning-timeline">
              <el-timeline-item
                v-for="log in currentDiagnosis.reasoningLogs || diagnosisProgress.logs"
                :key="`${log.step}-${log.title}`"
                :timestamp="`Step ${log.step}`"
                type="primary"
              >
                <strong>{{ log.title }}</strong>
                <p>{{ log.detail }}</p>
              </el-timeline-item>
            </el-timeline>
        </div>

        <div v-else-if="detailDialogType === 'graph'" class="tab-section dialog-tab-section">
            <div class="graph-visual-panel">
              <div class="graph-visual-header">
                <div>
                  <strong>Neo4j 图谱节点与关系</strong>
                  <span>{{ graphVisual.nodes.length }} 个节点 / {{ graphVisual.edges.length }} 条关系</span>
                </div>
                <small>GraphRAG 多跳关联拓扑</small>
              </div>
              <svg v-if="graphVisual.nodes.length" class="graph-visual-canvas" viewBox="0 0 760 320" role="img" aria-label="Neo4j 图谱关系">
                <defs>
                  <marker id="graph-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
                    <path d="M0,0 L0,6 L9,3 z" fill="#64748b" />
                  </marker>
                </defs>
                <g class="graph-edge-layer">
                  <g v-for="edge in graphVisual.edges" :key="edge.key">
                    <line :x1="edge.from.x" :y1="edge.from.y" :x2="edge.to.x" :y2="edge.to.y" marker-end="url(#graph-arrow)" />
                    <text :x="edge.labelX" :y="edge.labelY">{{ edge.label }}</text>
                  </g>
                </g>
                <g class="graph-node-layer">
                  <g v-for="node in graphVisual.nodes" :key="node.key" class="graph-node">
                    <circle :cx="node.x" :cy="node.y" :r="node.r" :fill="node.color" />
                    <text :x="node.x" :y="node.y - 2" text-anchor="middle">{{ node.shortLabel }}</text>
                    <text :x="node.x" :y="node.y + 14" text-anchor="middle" class="node-type">{{ node.type }}</text>
                  </g>
                </g>
              </svg>
              <el-empty v-else description="Neo4j 未返回真实图谱节点" :image-size="72" />
            </div>
            <el-table :data="realNeo4jEdges" border empty-text="Neo4j 未返回真实图谱边">
              <el-table-column prop="fromKey" label="起点" min-width="220" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="relationType" label="关系" width="120" />
              <el-table-column prop="toKey" label="终点" min-width="220" :show-overflow-tooltip="reportTooltipConfig" />
              <el-table-column prop="weight" label="权重" width="90" />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'snapshot'" class="tab-section dialog-tab-section">
            <div class="diagnosis-attachment-panel">
              <div class="snapshot-frame" :class="{ clickable: canRestoreCurrentDiagnosisBinding }" @click="canRestoreCurrentDiagnosisBinding && restoreDiagnosisBinding(currentDiagnosis)">
                <div class="snapshot-titlebar">
                  <div>
                    <strong>{{ chartSnapshot?.title || '诊断图表快照' }}</strong>
                    <span>{{ chartSnapshotMeta }}</span>
                  </div>
                  <el-button v-if="canRestoreCurrentDiagnosisBinding" link type="primary" @click.stop="restoreDiagnosisBinding(currentDiagnosis)">点击回溯</el-button>
                </div>
                <div class="snapshot-image-box">
                  <img v-if="chartSnapshot?.imageDataUrl" :src="chartSnapshot.imageDataUrl" alt="诊断报告绑定图表快照" />
                  <el-empty v-else description="暂无图表快照" :image-size="86" />
                </div>
              </div>
              <div class="diagnosis-subsection">
                <div class="subsection-title">
                  <strong>关联因素图表块</strong>
                  <span>{{ factorChartRows.length }} 个分析块</span>
                </div>
                <el-table :data="factorChartRows" border empty-text="暂无关联因素图表块">
                  <el-table-column prop="title" label="图表块" min-width="220" :show-overflow-tooltip="reportTooltipConfig" />
                  <el-table-column prop="chartTypeLabel" label="类型" width="110" />
                  <el-table-column prop="dataCount" label="数据点" width="90" />
                  <el-table-column prop="topFactor" label="首要因素" min-width="180" :show-overflow-tooltip="reportTooltipConfig" />
                </el-table>
              </div>
            </div>
        </div>

        <div v-else-if="detailDialogType === 'raw'" class="tab-section dialog-tab-section">
            <el-table :data="rawDataRows" height="420" border empty-text="暂无原始数据明细" row-key="sys_id">
              <el-table-column
                v-for="column in rawDataColumns"
                :key="column.prop"
                :prop="column.prop"
                :label="column.label"
                min-width="130"
                :show-overflow-tooltip="reportTooltipConfig"
              />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'fields'" class="tab-section dialog-tab-section">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="指标字段">{{ currentDiagnosis.metricFieldLabel || currentDiagnosis.metricField }}</el-descriptions-item>
              <el-descriptions-item label="时间字段">{{ currentDiagnosis.timeFieldLabel || currentDiagnosis.timeField || '未选择' }}</el-descriptions-item>
              <el-descriptions-item label="维度字段">{{ normalizedDimensionLabels.join('、') || '未选择' }}</el-descriptions-item>
              <el-descriptions-item label="异常类型">{{ currentDiagnosis.anomalyType || diagnosisForm.anomalyType }}</el-descriptions-item>
              <el-descriptions-item label="报告详细程度">{{ detailLevelLabel(currentDiagnosis.detailLevel || diagnosisForm.detailLevel) }}</el-descriptions-item>
              <el-descriptions-item label="数据表">{{ currentDiagnosis.tableName || selectedTableName }}</el-descriptions-item>
            </el-descriptions>
            <h3>业务字段</h3>
            <div class="field-label-grid">
              <el-tag v-for="item in fieldLabelEntries" :key="item.label" type="info">
                {{ item.label }}
              </el-tag>
            </div>
        </div>
      </el-dialog>

    </div>
  </div>

  <div class="panel graph-rag-progress-panel">
    <div class="progress-panel-title">
      <h2>GraphRAG 推理进度</h2>
    </div>
    <div class="graph-progress-flow">
      <template v-for="(step, index) in graphProgressSteps" :key="step.label">
        <div
          class="graph-progress-node"
          :class="{
            active: diagnosisProgress.percentage >= step.threshold,
            current: graphProgressCurrentIndex === index
          }"
        >
          <div class="node-icon">
            <el-icon><component :is="step.icon" /></el-icon>
          </div>
          <div>
            <strong>{{ step.label }}</strong>
            <span>{{ diagnosisProgress.percentage >= step.threshold ? '已完成' : (graphProgressCurrentIndex === index ? '进行中' : '待开始') }}</span>
          </div>
        </div>
        <div
          v-if="index < graphProgressSteps.length - 1"
          class="graph-progress-connector"
          :class="{ active: isGraphProgressLineActive(index) }"
        ></div>
      </template>
      <div class="graph-progress-status">
        <span>当前状态</span>
        <strong>{{ diagnosisProgress.step || '待开始' }}</strong>
        <small>预计耗时：2-5 分钟</small>
      </div>
    </div>
  </div>

  <div class="panel report-history">
    <div class="panel-header report-history-header">
      <div class="report-history-copy">
        <h2>历史诊断报告</h2>
      </div>
      <div class="history-actions">
        <el-select v-model="reportFilter.type" placeholder="报告类型" clearable class="history-filter">
          <el-option label="全部" value="" />
          <el-option label="简易" value="simple" />
          <el-option label="详细" value="detailed" />
        </el-select>
        <el-select v-model="reportFilter.anomaly" placeholder="异常类型" clearable class="history-filter">
          <el-option label="全部" value="" />
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
        <el-input v-model="reportFilter.keyword" class="history-search" placeholder="搜索报告名称" clearable>
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button @click="loadDiagnosisReports">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-table
      class="report-history-table"
      :data="pagedDiagnosisReports"
      max-height="360"
      size="small"
      empty-text="暂无历史报告"
      @row-click="loadDiagnosisReportDetail"
      @selection-change="handleReportSelectionChange"
    >
      <el-table-column prop="title" label="报告名称" min-width="210" :show-overflow-tooltip="reportTooltipConfig" />
      <el-table-column label="指标字段" min-width="120" :show-overflow-tooltip="reportTooltipConfig">
        <template #default="{ row }">
          <span :title="row.metricField">{{ reportMetricLabel(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="异常类型" min-width="110" :show-overflow-tooltip="reportTooltipConfig">
        <template #default="{ row }">
          <span>{{ anomalyTypeLabel(row.anomalyType) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数据表" min-width="130" :show-overflow-tooltip="reportTooltipConfig">
        <template #default="{ row }">
          <span>{{ row.tableDisplayName || row.tableName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="生成时间" min-width="160" :show-overflow-tooltip="reportTooltipConfig" />
      <el-table-column label="状态" width="96" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="reportStatus(row).type">{{ reportStatus(row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="210" align="left">
        <template #default="{ row }">
          <div class="history-row-actions">
            <el-button link type="primary" @click.stop="previewHistoryReport(row)">预览</el-button>
            <el-button link type="primary" @click.stop="exportHistoryReport(row)">导出</el-button>
            <el-button link type="primary" @click.stop="openRegenerateDialog(row)">重新生成</el-button>
            <el-button link type="danger" @click.stop="confirmDeleteReports([row])">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div class="history-footer">
      <span>共 {{ filteredDiagnosisReports.length }} 条</span>
      <el-pagination
        v-model:current-page="historyPage"
        v-model:page-size="historyPageSize"
        layout="prev, pager, next, sizes, jumper"
        :page-sizes="[10, 20, 50]"
        :total="filteredDiagnosisReports.length"
        small
      />
    </div>
  </div>

  <el-dialog v-model="previewFullscreenVisible" title="诊断报告全屏预览" width="90%" top="4vh" class="report-preview-dialog">
    <div class="report-preview-shell full-preview">
        <div class="report-reader">
          <div ref="reportPaperRef" class="paper">
            <div class="header">
              <div class="doc-type">{{ reportArticle.docType }}</div>
              <div class="title">{{ reportArticle.title }}</div>
              <div class="subtitle" v-html="reportArticle.subtitleHtml"></div>
              <div class="authors" v-html="reportArticle.authorsHtml"></div>
            </div>
            <template v-for="(block, index) in reportArticle.blocks" :key="`preview-${index}`">
            <div v-if="block.type === 'abstract'" class="abstract" v-html="block.html"></div>
            <h2 v-else-if="block.type === 'h2'">{{ block.text }}</h2>
            <h3 v-else-if="block.type === 'h3'">{{ block.text }}</h3>
            <table v-else-if="block.type === 'table'">
              <thead>
                <tr>
                  <th v-for="cell in block.headers" :key="cell" v-html="cell"></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                  <td
                    v-for="(cell, cellIndex) in row.visibleCells"
                    :key="cellIndex"
                    :rowspan="cell.rowspan"
                    :class="{ 'group-cell': cell.rowspan > 1 }"
                    v-html="cell.html"
                  ></td>
                </tr>
              </tbody>
            </table>
            <div v-else-if="block.type === 'caption'" class="figure-caption">{{ block.text }}</div>
            <div v-else-if="block.type === 'evidence'" class="evidence-block" v-html="block.html"></div>
            <button v-else-if="block.type === 'chart-link' && canRestoreCurrentDiagnosisBinding" class="report-chart-link" type="button" @click="restoreDiagnosisBinding(currentDiagnosis)">
              {{ block.text }}
            </button>
            <ul v-else-if="block.type === 'list'" class="report-list">
              <li v-for="item in block.htmlItems || block.items" :key="item" v-html="item"></li>
            </ul>
            <p v-else-if="block.type === 'paragraph'">{{ block.text }}</p>
            <div v-else class="report-spacer"></div>
          </template>
          </div>
        </div>
    </div>
  </el-dialog>

  <div class="offscreen-report-export" aria-hidden="true">
    <div class="report-reader">
      <div ref="exportPaperRef" class="paper">
        <div class="header">
          <div class="doc-type">{{ exportArticle.docType }}</div>
          <div class="title">{{ exportArticle.title }}</div>
          <div class="subtitle" v-html="exportArticle.subtitleHtml"></div>
          <div class="authors" v-html="exportArticle.authorsHtml"></div>
        </div>
        <template v-for="(block, index) in exportArticle.blocks" :key="`export-${index}`">
          <div v-if="block.type === 'abstract'" class="abstract" v-html="block.html"></div>
          <h2 v-else-if="block.type === 'h2'">{{ block.text }}</h2>
          <h3 v-else-if="block.type === 'h3'">{{ block.text }}</h3>
          <table v-else-if="block.type === 'table'">
            <thead>
              <tr>
                <th v-for="cell in block.headers" :key="cell" v-html="cell"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                <td
                  v-for="(cell, cellIndex) in row.visibleCells"
                  :key="cellIndex"
                  :rowspan="cell.rowspan"
                  :class="{ 'group-cell': cell.rowspan > 1 }"
                  v-html="cell.html"
                ></td>
              </tr>
            </tbody>
          </table>
          <div v-else-if="block.type === 'caption'" class="figure-caption">{{ block.text }}</div>
          <div v-else-if="block.type === 'evidence'" class="evidence-block" v-html="block.html"></div>
          <ul v-else-if="block.type === 'list'" class="report-list">
            <li v-for="item in block.htmlItems || block.items" :key="item" v-html="item"></li>
          </ul>
          <div v-else-if="block.type === 'image'" class="report-image-block">
            <div class="snapshot-titlebar">
              <div>
                <strong>{{ block.title }}</strong>
                <span>{{ block.meta }}</span>
              </div>
            </div>
            <div class="snapshot-image-box">
              <img :src="block.src" :alt="block.alt || block.title" />
            </div>
          </div>
          <p v-else-if="block.type === 'paragraph'">{{ block.text }}</p>
          <div v-else class="report-spacer"></div>
        </template>
      </div>
    </div>
  </div>

  <el-dialog v-model="regenerateDialogVisible" title="重新生成诊断报告" width="420px">
    <el-form label-position="top">
      <el-form-item label="报告详细程度">
        <el-radio-group v-model="regenerateForm.detailLevel">
          <el-radio-button label="simple">简易</el-radio-button>
          <el-radio-button label="detailed">详细</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="异常类型">
        <el-select v-model="regenerateForm.anomalyType" class="full-width">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="regenerateDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="diagnosisLoading" @click="confirmRegenerateReport">重新生成</el-button>
    </template>
  </el-dialog>
</div>
</template>

<script setup>
import { computed, inject, nextTick, ref } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import {
  Check,
  Coin,
  Delete,
  Document,
  DocumentChecked,
  FolderChecked,
  Link,
  Loading,
  Refresh,
  Search,
  Share,
  TrendCharts,
  UploadFilled,
  Warning
} from '@element-plus/icons-vue'
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { API_BASE, http } from '../../api/http'

const {
  activeModule,
  currentDiagnosis,
  dateFields,
  diagnosisForm,
  diagnosisLoading,
  diagnosisProgress,
  diagnosisReports,
  dimensionCandidateFields,
  exportDiagnosisReport,
  knowledgeDocFile,
  knowledgeDocFiles,
  knowledgeDocUploadFiles,
  knowledgeDocUploading,
  knowledgeDocs,
  deleteKnowledgeDoc,
  deleteDiagnosisReports,
  loadDiagnosisReportDetail,
  loadDiagnosisReports,
  numericFields,
  onKnowledgeDocChange,
  onKnowledgeDocRemove,
  regenerateDiagnosisReport,
  restoreDiagnosisBinding,
  runDiagnosis,
  selectedTableName,
  tables,
  uploadTables,
  officialQueryTables,
  uploadKnowledgeDoc
} = inject('workbench')

const previewFullscreenVisible = ref(false)
const regenerateDialogVisible = ref(false)
const reportPaperRef = ref(null)
const exportPaperRef = ref(null)
const reportTooltipConfig = {
  popperClass: 'diagnosis-report-tooltip',
  placement: 'top',
  showAfter: 250
}
const reportFilter = ref({ type: '', anomaly: '' })
const historyPage = ref(1)
const historyPageSize = ref(10)
const selectedReportRows = ref([])
const reportGenerateForm = ref({ detailLevel: 'detailed', anomalyType: 'fluctuation' })
const regenerateTarget = ref(null)
const regenerateForm = ref({ detailLevel: 'detailed', anomalyType: 'fluctuation' })
const detailDialogVisible = ref(false)
const detailDialogType = ref('evidence')
const exportOptions = ref({
  includeSnapshots: true,
  includeReasoningLogs: true,
  enablePdfEncryption: false
})
const reportDetailEntries = [
  { type: 'evidence', label: '证据链' },
  { type: 'reasoning', label: '图谱推理' },
  { type: 'graph', label: 'Neo4j 图谱' },
  { type: 'snapshot', label: '图表快照' },
  { type: 'raw', label: '原始数据' },
  { type: 'fields', label: '字段配置' }
]
const emptyReportFeatures = [
  '异常数据节点识别',
  '数据波动数值与趋势分析',
  '异常关联因素分析',
  'GraphRAG 多跳推理过程',
  'Neo4j 图谱证据链',
  '根因定位结论',
  '改进建议与行动方案',
  'PDF / Word 导出内容'
]
const graphProgressSteps = [
  { label: '任务创建', threshold: 10, icon: UploadFilled },
  { label: '知识证据检查', threshold: 35, icon: DocumentChecked },
  { label: '多跳推理', threshold: 70, icon: Link },
  { label: '根因定位', threshold: 88, icon: Search },
  { label: '报告生成', threshold: 100, icon: FolderChecked }
]
const detailDialogTitle = computed(() =>
  reportDetailEntries.find(item => item.type === detailDialogType.value)?.label || '报告详情'
)

const openDetailDialog = (type) => {
  detailDialogType.value = type
  detailDialogVisible.value = true
}

const filteredDiagnosisReports = computed(() => {
  const keyword = String(reportFilter.value.keyword || '').trim().toLowerCase()
  return (diagnosisReports.value || []).filter((report) => {
    const typePass = !reportFilter.value.type || report?.detailLevel === reportFilter.value.type
    const anomalyPass = !reportFilter.value.anomaly || report?.anomalyType === reportFilter.value.anomaly
    const keywordPass = !keyword || [
      report?.title,
      report?.tableName,
      report?.tableDisplayName,
      report?.metricField,
      report?.metricFieldLabel,
      businessSummaryText(report)
    ].some(value => String(value || '').toLowerCase().includes(keyword))
    return typePass && anomalyPass && keywordPass
  })
})

const pagedDiagnosisReports = computed(() => {
  const start = (historyPage.value - 1) * historyPageSize.value
  return filteredDiagnosisReports.value.slice(start, start + historyPageSize.value)
})

const graphProgressCurrentIndex = computed(() => {
  const percentage = Number(diagnosisProgress.value?.percentage || 0)
  const index = graphProgressSteps.findIndex(step => percentage < step.threshold)
  return index === -1 ? graphProgressSteps.length - 1 : index
})

const isGraphProgressLineActive = (index) => {
  if (index >= graphProgressSteps.length - 1) return false
  return Number(diagnosisProgress.value?.percentage || 0) >= graphProgressSteps[index].threshold
}

const selectedReportIds = computed(() =>
  selectedReportRows.value
    .map(row => Number(row?.id))
    .filter(id => Number.isFinite(id) && id > 0)
)

const handleReportSelectionChange = (rows) => {
  selectedReportRows.value = rows || []
}

const reportMetricLabel = (report) => {
  const explicit = String(report?.metricFieldLabel || '').trim()
  if (explicit && !looksPhysicalField(explicit)) return explicit
  const field = String(report?.metricField || '').trim()
  if (!field) return '-'
  const mapped = readableValueLabel(field, report?.fieldLabels)
  if (mapped && !looksPhysicalField(mapped)) return mapped
  const knownField = [
    ...(numericFields.value || []),
    ...(dimensionCandidateFields.value || []),
    ...(dateFields.value || [])
  ].find(item => item?.columnName === field)
  return knownField?.displayName || knownField?.sourceFieldName || field
}

const detailLevelLabel = (value) => {
  if (value === 'simple') return '简易'
  if (value === 'detailed') return '详细'
  return value || '-'
}

const reportDetailLevel = computed(() => currentDiagnosis.value?.detailLevel || diagnosisForm.value.detailLevel || 'detailed')
const isSimpleReport = computed(() => reportDetailLevel.value === 'simple')
const romanSection = (value) => ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][Math.max(0, value - 1)] || String(value)

const anomalyTypeLabel = (value) => {
  const map = {
    fluctuation: '波动异常',
    structure: '结构异常',
    trend: '趋势异常'
  }
  return map[value] || value || '-'
}

const reportStatus = (row = {}) => {
  const status = String(row.status || row.state || '').toUpperCase()
  if (status.includes('RUN') || status.includes('GENERATING')) return { label: '生成中', type: 'primary' }
  if (status.includes('FAIL')) return { label: '失败', type: 'danger' }
  if (row.id || row.resultJson) return { label: '已生成', type: 'success' }
  return { label: '待开始', type: 'info' }
}

const knownFieldLabels = () => {
  const labels = {}
  ;[
    ...(numericFields.value || []),
    ...(dimensionCandidateFields.value || []),
    ...(dateFields.value || [])
  ].forEach(field => {
    const column = String(field?.columnName || '').trim()
    const label = String(field?.displayName || field?.sourceFieldName || '').trim()
    if (column && label && !looksPhysicalField(label)) {
      labels[column] = label
    }
  })
  return labels
}

const escapeRegExp = (value) => String(value || '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

const replaceFieldNamesInText = (value, report = {}) => {
  let text = String(value || '').trim()
  if (!text) return ''
  const labels = {
    ...knownFieldLabels(),
    ...(currentDiagnosis.value?.fieldLabels || {}),
    ...(report?.fieldLabels || {})
  }
  const metricField = String(report?.metricField || currentDiagnosis.value?.metricField || '').trim()
  const metricLabel = String(reportMetricLabel(report || currentDiagnosis.value || {}) || '').trim()
  if (metricField && metricLabel && !looksPhysicalField(metricLabel)) {
    labels[metricField] = metricLabel
  }
  Object.entries(labels)
    .filter(([field, label]) => field && label && field !== label && !looksPhysicalField(label))
    .sort((a, b) => b[0].length - a[0].length)
    .forEach(([field, label]) => {
      text = text.replace(new RegExp(`\\b${escapeRegExp(field)}\\b`, 'gi'), label)
    })
  return text
}

const businessSummaryText = (report = {}) => {
  const summary = replaceFieldNamesInText(report?.summary, report)
  if (summary) return summary
  const stats = report?.statistics || {}
  const metric = reportMetricLabel(report)
  const count = stats.count || rawDataRows.value.length
  const total = stats.total
  return `本次围绕「${metric}」分析 ${formatInteger(count)} 条有效样本，合计 ${formatReportValue(total)}。`
}

const chartSnapshot = computed(() => {
  const snapshot = currentDiagnosis.value?.chartSnapshot
  if (!snapshot || typeof snapshot !== 'string') return snapshot
  try {
    return JSON.parse(snapshot)
  } catch {
    return null
  }
})

const parseMaybeJson = (value) => {
  if (!value || typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

const diagnosisBindingRoute = (report = currentDiagnosis.value) => {
  const binding = parseReportBinding(report || {})
  const snapshot = chartSnapshot.value || parseMaybeJson(report?.chartSnapshot) || binding.chartSnapshot || {}
  return String(binding.route || snapshot.sourceRoute || '').trim().toLowerCase()
}

const canRestoreCurrentDiagnosisBinding = computed(() =>
  ['chat', 'dashboard'].includes(diagnosisBindingRoute(currentDiagnosis.value))
)

const selectedKnowledgeDocNames = computed(() => {
  const pendingFiles = [
    ...(knowledgeDocUploadFiles.value || []).map(item => item.name || item.raw?.name),
    ...(knowledgeDocFiles.value || []).map(file => file.name)
  ].filter(Boolean)
  const names = pendingFiles.length
    ? [...new Set(pendingFiles)]
    : (knowledgeDocs.value || []).map(doc => doc.title || doc.fileName).filter(Boolean)
  if (!names.length) return '未选择文件'
  if (names.length <= 3) return names.join('、')
  return `${names.slice(0, 3).join('、')} 等 ${names.length} 个文件`
})

const knowledgeDocStatusText = computed(() => {
  const suffix = knowledgeDocs.value?.length
    ? '已纳入 GraphRAG 文档扫描和根因证据召回。'
    : '用于 GraphRAG 文档扫描和根因证据召回。'
  return selectedKnowledgeDocNames.value === '未选择文件'
    ? selectedKnowledgeDocNames.value
    : `${selectedKnowledgeDocNames.value}，${suffix}`
})

const anomalyMarkers = computed(() => {
  const markers = currentDiagnosis.value?.anomalyMarkers || chartSnapshot.value?.anomalyMarkers || []
  return (Array.isArray(markers) ? markers : []).map((item, index) => ({
    key: item.key || `${item.label || 'marker'}-${index}`,
    label: item.label || item.name || `异常点 ${index + 1}`,
    valueLabel: item.valueLabel || `指标值：${item.value ?? '-'}`,
    reason: item.reason || item.tag || '超过波动阈值，建议回溯来源数据'
  }))
})

const rawDataRows = computed(() => {
  const rows = currentDiagnosis.value?.rawDataRows || currentDiagnosis.value?.queryRows || chartSnapshot.value?.data || []
  return Array.isArray(rows) ? rows : []
})

const rawDataColumns = computed(() => {
  const first = rawDataRows.value.find(row => row && typeof row === 'object')
  return first
    ? Object.keys(first).slice(0, 10).map(column => ({
      prop: column,
      label: readableValueLabel(column, {
        ...(currentDiagnosis.value?.fieldLabels || {}),
        ...knownFieldLabels()
      })
    }))
    : []
})

const fieldLabelEntries = computed(() => {
  const labels = {
    ...knownFieldLabels(),
    ...(currentDiagnosis.value?.fieldLabels || {})
  }
  const seen = new Set()
  return Object.values(labels)
    .map(label => String(label || '').trim())
    .filter(label => label && !looksPhysicalField(label))
    .filter(label => {
      if (seen.has(label)) return false
      seen.add(label)
      return true
    })
    .map(label => ({ label }))
})

const graphVisual = computed(() => {
  const report = currentDiagnosis.value || {}
  const rawNodes = Array.isArray(report.graphPath?.nodes) ? report.graphPath.nodes : []
  const rawEdges = Array.isArray(report.graphPath?.edges) ? report.graphPath.edges : []
  const nodes = rawNodes.slice(0, 12).map((node, index, list) => {
    const key = node.nodeKey || node.key || node.sourceId || node.id || `${node.nodeType || 'node'}-${index}`
    const angle = list.length <= 1 ? 0 : (Math.PI * 2 * index / list.length) - Math.PI / 2
    const radiusX = list.length <= 4 ? 210 : 280
    const radiusY = list.length <= 4 ? 92 : 118
    const x = Math.round(380 + Math.cos(angle) * radiusX)
    const y = Math.round(160 + Math.sin(angle) * radiusY)
    const type = String(node.nodeType || node.sourceType || 'NODE')
    const label = String(node.label || node.name || node.sourceId || key)
    return {
      raw: node,
      key,
      label,
      shortLabel: trimDisplay(label, 8),
      type: trimDisplay(type, 12),
      x,
      y,
      r: type.includes('ROOT') ? 38 : 34,
      color: graphNodeColor(type)
    }
  })
  const nodeMap = new Map(nodes.flatMap(node => [
    [node.key, node],
    [node.raw.sourceId, node],
    [node.raw.nodeKey, node],
    [node.raw.label, node]
  ].filter(([key]) => key != null && key !== '')))
  const edges = rawEdges.map((edge, index) => {
    const from = nodeMap.get(edge.fromKey) || nodeMap.get(edge.from)
    const to = nodeMap.get(edge.toKey) || nodeMap.get(edge.to)
    if (!from || !to || from.key === to.key) return null
    return {
      key: `${from.key}-${to.key}-${index}`,
      from,
      to,
      label: trimDisplay(String(edge.relationType || edge.type || 'RELATES'), 14),
      labelX: Math.round((from.x + to.x) / 2),
      labelY: Math.round((from.y + to.y) / 2 - 6)
    }
  }).filter(Boolean)
  return { nodes, edges: edges.slice(0, 18) }
})

const realNeo4jEdges = computed(() =>
  Array.isArray(currentDiagnosis.value?.graphPath?.edges) ? currentDiagnosis.value.graphPath.edges : []
)

const chartSnapshotMeta = computed(() => {
  const snapshot = chartSnapshot.value || {}
  const chartType = chartTypeName(snapshot.chartType)
  const source = chartSnapshotSourceLabel(snapshot)
  const count = Array.isArray(snapshot.data) ? snapshot.data.length : rawDataRows.value.length
  return `${chartType} · ${source} · ${count || 0} 个数据点`
})

const chartSnapshotSourceLabel = (snapshot = {}) => {
  if (snapshot.source === 'server-generated') return '后端自动生成'
  if (snapshot.sourceRoute === 'dashboard') return '看板图表'
  return '对话分析图表'
}

const anomalyNodeRows = computed(() => normalizedReportMarkers.value.map((marker, index) => ({
  key: `${marker.window}-${index}`,
  time: marker.window || `异常点 ${index + 1}`,
  valueLabel: formatReportValue(marker.value),
  type: marker.type || outlierType(marker.value, numericValue(currentDiagnosis.value?.statistics?.avg)),
  reason: marker.reason || deviationText(marker.value, numericValue(currentDiagnosis.value?.statistics?.avg))
})))

const factorChartRows = computed(() => {
  const blocks = Array.isArray(currentDiagnosis.value?.factorChartBlocks) ? currentDiagnosis.value.factorChartBlocks : []
  return blocks.map((block, index) => {
    const data = Array.isArray(block.data) ? block.data : []
    const top = data[0] || {}
    return {
      key: `${block.title || 'chart'}-${index}`,
      title: block.title || `关联因素图表块 ${index + 1}`,
      chartTypeLabel: chartTypeName(block.chartType),
      dataCount: data.length,
      topFactor: top.name || top.time || top.label ? `${top.name || top.time || top.label}：${formatReportValue(top.value)}` : '-'
    }
  })
})

const overviewMetricCards = computed(() => {
  const stats = currentDiagnosis.value?.statistics || {}
  return [
    { label: '有效记录', value: formatInteger(stats.count), icon: DocumentChecked, tone: 'document' },
    { label: '指标合计', value: formatReportValue(stats.total), icon: Coin, tone: 'money' },
    { label: '平均值', value: formatReportValue(stats.avg), icon: TrendCharts, tone: 'trend' },
    { label: '异常节点', value: formatInteger(anomalyNodeRows.value.length), icon: Warning, tone: 'warning' },
    { label: '图谱节点', value: formatInteger(graphVisual.value.nodes.length), icon: Share, tone: 'graph' },
    { label: '图谱关系', value: formatInteger(graphVisual.value.edges.length), icon: Link, tone: 'link' }
  ]
})

const diagnosisSummaryText = computed(() => {
  const report = currentDiagnosis.value || {}
  const stats = report.statistics || {}
  const metric = report.metricFieldLabel || report.metricField || '指标'
  const rootCause = Array.isArray(report.rootCauses) && report.rootCauses.length
    ? report.rootCauses[0].causeType
    : '证据不足，暂未形成单一收敛根因'
  const conclusion = Array.isArray(report.rootCauses) && report.rootCauses.length
    ? `将主要根因指向「${rootCause}」`
    : `将当前结论标定为「${rootCause}」`
  return `本次围绕「${metric}」分析 ${formatInteger(stats.count || rawDataRows.value.length)} 条有效样本，合计 ${formatReportValue(stats.total)}，均值 ${formatReportValue(stats.avg)}，识别出 ${anomalyNodeRows.value.length} 个关键异常节点。系统结合维度贡献、GraphRAG 证据链与 Neo4j 图谱关系，${conclusion}。`
})

const dimensionBreakdownRows = computed(() => {
  const rows = buildDimensionTableRows(currentDiagnosis.value || {}, currentDiagnosis.value?.metricFieldLabel || currentDiagnosis.value?.metricField || '指标')
  return rows.map(row => ({
    scope: cleanInlineMarkdown(row[0]),
    dimension: cleanInlineMarkdown(row[1]),
    factor: cleanInlineMarkdown(row[2]),
    valueLabel: cleanInlineMarkdown(row[3]),
    ratioLabel: cleanInlineMarkdown(row[4])
  }))
})

const reportTextBlocks = computed(() => {
  const text = currentDiagnosis.value?.reportMarkdown || ''
  const blocks = []
  let listItems = []
  let metaLines = []
  const flushList = () => {
    if (listItems.length) {
      blocks.push({ type: 'list', items: listItems })
      listItems = []
    }
  }
  const flushMeta = () => {
    if (metaLines.length) {
      blocks.push({ type: 'meta', lines: [...metaLines], html: renderInlineMarkdown(metaLines.join('<br>')) })
      metaLines = []
    }
  }
  const lines = text.split('\n')
  for (let index = 0; index < lines.length; index += 1) {
    const rawLine = lines[index]
    const line = rawLine.trim()
    if (line === ':::report-meta') {
      flushList()
      metaLines = []
      index += 1
      while (index < lines.length && lines[index].trim() !== ':::') {
        metaLines.push(lines[index].trim().replace(/\s{2}$/, ''))
        index += 1
      }
      flushMeta()
      continue
    }
    if (!line) {
      flushList()
      flushMeta()
      blocks.push({ type: 'blank' })
      continue
    }
    if (line.startsWith('> ')) {
      flushList()
      flushMeta()
      const quote = line.replace(/^>\s*/, '')
      blocks.push({
        type: quote.includes('Abstract / 诊断摘要') ? 'abstract' : 'evidence',
        html: renderInlineMarkdown(quote)
      })
      continue
    }
    if (isMarkdownTableStart(lines, index)) {
      flushList()
      flushMeta()
      const headers = parseMarkdownTableRow(lines[index])
      index += 2
      const rows = []
      while (index < lines.length && /^\s*\|.*\|\s*$/.test(lines[index])) {
        rows.push(parseMarkdownTableRow(lines[index]))
        index += 1
      }
      index -= 1
      blocks.push({ type: 'table', headers, rows })
      continue
    }
    if (line.startsWith('### ')) {
      flushList()
      flushMeta()
      blocks.push({ type: 'h3', text: cleanInlineMarkdown(line.slice(4)) })
      continue
    }
    if (line.startsWith('## ')) {
      flushList()
      flushMeta()
      blocks.push({ type: 'h2', text: cleanInlineMarkdown(line.slice(3)) })
      continue
    }
    if (line.startsWith('# ')) {
      flushList()
      flushMeta()
      blocks.push({ type: 'h1', text: cleanInlineMarkdown(line.slice(2)) })
      continue
    }
    if (/^\*[^*].*表\s*[IVXLC]+.*\*$/.test(line)) {
      flushList()
      flushMeta()
      blocks.push({ type: 'caption', text: cleanInlineMarkdown(line.replace(/^\*/, '').replace(/\*$/, '')) })
      continue
    }
    if (/^[-*]\s+/.test(line)) {
      listItems.push(cleanInlineMarkdown(line.replace(/^[-*]\s+/, '')))
      continue
    }
    flushList()
    flushMeta()
    const clean = cleanInlineMarkdown(line)
    blocks.push({ type: /图表|快照|异常节点|数据明细/.test(clean) ? 'chart-link' : 'paragraph', text: clean })
  }
  flushList()
  flushMeta()
  return blocks
})

const cleanInlineMarkdown = (value) => String(value || '')
  .replace(/\*\*(.*?)\*\*/g, '$1')
  .replace(/__(.*?)__/g, '$1')
  .replace(/`([^`]+)`/g, '$1')
  .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')

const displayEvidenceText = (value) => {
  const text = cleanInlineMarkdown(value || '暂无补充证据说明。')
    .replace(/#{1,6}\s*/g, '')
    .replace(/\s*[-*]\s+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text || '暂无补充证据说明。'
}

const limitReportText = (value, maxLength = 320) => {
  const text = displayEvidenceText(value)
  if (text.length <= maxLength) return text
  return `${text.slice(0, Math.max(0, maxLength - 1)).trim()}。`
}

const firstEvidenceSentence = (value) => {
  const text = displayEvidenceText(value)
    .replace(/证据：(?=《).*/, '')
    .replace(/《[^》]+》/g, '')
    .trim()
  if (!text) return ''
  const ends = ['。', '；', ';']
    .map(separator => text.indexOf(separator))
    .filter(index => index >= 0)
  const end = ends.length ? Math.min(...ends) : -1
  return limitReportText(end >= 0 ? text.slice(0, end + 1) : text, 140)
}

const addEvidenceSignal = (signals, text, pattern, label) => {
  if (pattern.test(text) && !signals.includes(label)) signals.push(label)
}

const evidenceReportSummary = (cause = {}, metricLabel = '指标') => {
  const rawEvidence = displayEvidenceText(cause.evidence || '')
  const causeType = displayEvidenceText(cause.causeType || '根因假设')
  const impactField = displayEvidenceText(cause.impactField || metricLabel)
  if (!rawEvidence || rawEvidence === '暂无补充证据说明。') {
    return '关键证据：当前证据链未命中文档原文，结论主要依据异常节点、维度贡献和图谱关系综合评估。建议补充业务复盘材料后重新核验。'
  }

  const signals = []
  addEvidenceSignal(signals, rawEvidence, /供应链|补货|SKU|库存|仓配/, '供应链补货、区域库存或 SKU 可得性')
  addEvidenceSignal(signals, rawEvidence, /满减|促销|折扣|活动|大促|价格策略/, '促销退坡、价格策略或活动状态变化')
  addEvidenceSignal(signals, rawEvidence, /企业客户|审批|采购节奏|大客户/, '企业客户审批节奏和采购周期')
  addEvidenceSignal(signals, rawEvidence, /渠道|转化率|线上|直营|经销/, '渠道结构及转化率波动')
  addEvidenceSignal(signals, rawEvidence, /物流|调拨|时效|退款|取消/, '物流时效、跨区调拨或履约体验')

  const context = firstEvidenceSentence(rawEvidence)
  let summary = '关键证据：'
  if (context) {
    summary += context
    if (!/[。；;]$/.test(context)) summary += '。'
  } else {
    summary += `证据链显示「${causeType}」与「${impactField}」存在关联。`
  }
  summary += signals.length
    ? `文档证据集中指向${signals.join('、')}等影响因素。`
    : '文档证据与异常节点、维度贡献结果方向一致。'
  summary += '建议优先核验对应日期窗口内的业务口径、运营配置和关键订单记录。'
  return limitReportText(summary, 320)
}

const renderInlineMarkdown = (value) => escapeHtml(String(value || ''))
  .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
  .replace(/__(.*?)__/g, '<b>$1</b>')
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')

const escapeHtml = (value) => String(value || '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const isMarkdownTableStart = (lines, index) => {
  const current = lines[index] || ''
  const next = lines[index + 1] || ''
  return /^\s*\|.*\|\s*$/.test(current) && /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(next)
}

const parseMarkdownTableRow = (line) => String(line || '')
  .trim()
  .replace(/^\|/, '')
  .replace(/\|$/, '')
  .split('|')
  .map(cell => renderInlineMarkdown(cell.trim()))

const reportArticle = computed(() => {
  const report = currentDiagnosis.value || {}
  const blocks = []
  const metricLabel = report.metricFieldLabel || report.metricField || '指标'
  const tableName = report.tableName || selectedTableName.value || 'biz_data'
  const createdAt = String(report.createdAt || '').slice(0, 10) || new Date().toISOString().slice(0, 10)
  const stats = report.statistics || {}
  const markers = normalizedReportMarkers.value
  const rootCauses = Array.isArray(report.rootCauses) ? report.rootCauses : []
  const suggestions = Array.isArray(report.suggestions) ? report.suggestions : []
  const graphNodes = Array.isArray(report.graphPath?.nodes) ? report.graphPath.nodes : []
  const graphEdges = Array.isArray(report.graphPath?.edges) ? report.graphPath.edges : []
  const dimensions = normalizedDimensionLabels.value
  const timeLabel = report.timeFieldLabel || report.timeField || '日期'
  const rootCauseName = rootCauses[0]?.causeType || '证据不足，暂未形成单一收敛根因'
  const rootCauseConclusion = rootCauses.length
    ? `最终将核心根因指向「${escapeHtml(rootCauseName)}」。`
    : `当前未形成单一收敛根因，系统将结论标定为「${escapeHtml(rootCauseName)}」。`
  const dimensionChain = dimensions.join('/') || '未选择维度字段'
  const confidenceBands = confidenceBandText(rootCauses)
  const simple = isSimpleReport.value
  const article = {
    docType: 'Diagnostic Analysis Report | Insight Spark System',
    title: simple ? '业务指标异常诊断简报' : '基于 GraphRAG 的多跳关联推理与业务指标异常归因分析',
    subtitleHtml: `—— 以数据集 <span class="code-inline">${escapeHtml(tableName)}</span> ${escapeHtml(metricLabel)}指标为例`,
    authorsHtml: `自动生成环境: 智能诊断引擎 (Build: 2026.05) <br>诊断时间: ${escapeHtml(createdAt)}`,
    blocks
  }

  blocks.push({
    type: 'abstract',
    html: `<span class="abstract-title">Abstract / 诊断摘要：</span>
        本次分析围绕核心业务指标「${escapeHtml(metricLabel)}」展开。系统在有效观测区间内提取了 ${formatInteger(stats.count || rawDataRows.value.length)} 条样本记录进行异常扫描，识别出 ${markers.length} 个显著异常节点。统计结果显示，样本总计数值为 ${formatReportValue(stats.total)}，均值 (<span class="math">μ</span>) 为 ${formatReportValue(stats.avg)}，区间极值分别为 <span class="math">Max</span> = ${formatReportValue(stats.max)} 与 <span class="math">Min</span> = ${formatReportValue(stats.min)}。系统融合 GraphRAG、Neo4j 知识图谱与文档证据（涉及 ${graphNodes.length} 个节点与 ${graphEdges.length} 条边），${rootCauseConclusion}${simple ? '本文档聚焦异常结论、关键证据与处置建议。' : '本文档详细记录了数据特征、多跳推理路径及多维度异质性分析结果。'}`
  })

  blocks.push({ type: 'h2', text: 'I. 描述性统计与异常检测 (Statistical Characteristics)' })
  blocks.push({
    type: 'paragraph',
    text: `针对数据集 ${tableName} 中的目标变量「${metricLabel}」，系统执行了基准扫描。有效样本量 N = ${formatInteger(stats.count || rawDataRows.value.length)}。基于分布特征，系统标定了 ${markers.length} 个显著偏离常态分布区间的异常观测点。具体检测结果如表 I 所示。`
  })
  blocks.push({
    type: 'table',
    headers: ['观测日期 (Time Window)', '指标数值 (Value)', '偏离度 / 统计检验量', '异常标定类型'].map(renderInlineMarkdown),
    rows: buildReportTableRows([], buildAnomalyTableRows(markers, stats))
  })
  blocks.push({ type: 'caption', text: '表 I. 核心指标异常节点识别清单' })

  blocks.push({ type: 'h2', text: 'II. 图谱知识检索与多跳推理链路 (GraphRAG Reasoning)' })
  blocks.push({
    type: 'paragraph',
    text: `为克服单一数据视角的局限性，本次诊断未仅停留在字段级的相关性分析，而是构建了基于 GraphRAG 的因果推理拓扑。推理链路严格遵循以下演进次序：数据表关联 → 指标层映射 → 业务维度空间拆解（${dimensionChain}） → 时序变量回溯（${timeLabel}）。`
  })
  blocks.push({
    type: 'paragraph',
    text: `在图计算阶段，系统调用 Neo4j 图数据库，遍历 ${graphNodes.length} 个关联实体节点与 ${graphEdges.length} 条语义关系边，${rootCauses.length ? `最终推演的收敛根因为「${rootCauseName}」。` : `当前尚未推演出单一收敛根因。`}`
  })
  blocks.push({
    type: 'evidence',
    html: buildEvidenceHtml(report)
  })
  blocks.push({
    type: 'evidence',
    html: buildHistoricalEvidenceHtml(report)
  })

  blocks.push({ type: 'h2', text: 'III. 归因定位与置信度评估 (Attribution Analysis)' })
  blocks.push({
    type: 'paragraph',
    text: `基于上述多跳推理逻辑，系统对诱发指标波动的潜在因素进行了权重分配与显著性评估。当前根因结论覆盖${confidenceBands}置信区间：`
  })
  blocks.push({
    type: 'list',
    htmlItems: rootCauses.length ? rootCauses.map(cause => attributionListHtml(cause, metricLabel)) : [
      `<b>[置信度: 0.45 / LOW] 证据不足：</b> 主要影响对象为「${escapeHtml(metricLabel)}」。当前样本未形成高置信度根因，建议补充业务维度、时间窗口与知识文档后重新诊断。`
    ]
  })

  if (!simple) {
    blocks.push({ type: 'h2', text: 'IV. 多维度异质性分析 (Multidimensional Heterogeneity)' })
    blocks.push({
      type: 'paragraph',
      text: `为进一步剥离异常值的结构来源，本节对${dimensions.length ? `${dimensions.length} 大核心维度（${dimensions.join('、')}）` : '核心业务维度'}进行了下钻与贡献度拆解。表 II 优先展示异常节点子集贡献；当异常节点明细不足时，回退展示全样本贡献分布。`
    })
    blocks.push({
      type: 'table',
      headers: ['分析口径 (Scope)', '一阶维度 (Dimension)', '二阶因子 (Factor)', '贡献值 (Value)', '口径内占比 (Ratio)'].map(renderInlineMarkdown),
      rows: buildReportTableRows(['一阶维度 (Dimension)'], buildDimensionTableRows(report, metricLabel))
    })
    blocks.push({ type: 'caption', text: '表 II. 核心业务维度贡献与相对比重拆解（优先异常节点口径）' })
  }

  blocks.push({ type: 'h2', text: `${simple ? 'IV' : 'V'}. 结论与对策建议 (Conclusion & Recommendations)` })
  blocks.push({
    type: 'paragraph',
    text: '综上分析，本次指标异动具有显著的结构性与节点性特征。为防范潜在的业务连续性风险并优化数据观测模型，提出以下干预建议：'
  })
  blocks.push({
    type: 'list',
    htmlItems: suggestions.length ? suggestions.map(item => escapeHtml(item)) : fallbackReportSuggestions(metricLabel, dimensions, timeLabel, report).map(item => escapeHtml(item))
  })

  blocks.push({ type: 'h2', text: `${simple ? 'V' : 'VI'}. 报告绑定与回溯说明 (Traceability Binding)` })
  blocks.push({
    type: 'paragraph',
    text: buildTraceabilityText(report)
  })

  for (const block of reportTextBlocks.value) {
    if (['h1', 'meta', 'abstract', 'table', 'caption', 'evidence'].includes(block.type)) continue
    const duplicateSection = block.type === 'h2' && /^[IVX]+\./.test(block.text)
    if (!duplicateSection && block.type !== 'blank') {
      // 保留后端额外生成的非标准段落时，也让它继承同一版式。
    }
  }
  return article
})

const exportArticle = computed(() => {
  const article = {
    ...reportArticle.value,
    blocks: [...(reportArticle.value.blocks || [])]
  }
  let nextSection = isSimpleReport.value ? 6 : 7
  if (exportOptions.value.includeSnapshots && chartSnapshot.value?.imageDataUrl) {
    article.blocks.push({ type: 'h2', text: `${romanSection(nextSection)}. 图表快照 (Chart Snapshot)` })
    nextSection += 1
    article.blocks.push({
      type: 'paragraph',
      text: `本节附加诊断报告绑定的图表快照。图表类型为${chartTypeName(chartSnapshot.value.chartType)}，数据来源为${chartSnapshotSourceLabel(chartSnapshot.value)}，共包含 ${Array.isArray(chartSnapshot.value.data) ? chartSnapshot.value.data.length : rawDataRows.value.length} 个数据点，可用于回溯异常节点所在的原始对话或看板。`
    })
    article.blocks.push({
      type: 'image',
      title: chartSnapshot.value.title || '诊断图表快照',
      meta: chartSnapshotMeta.value,
      src: chartSnapshot.value.imageDataUrl,
      alt: '诊断报告绑定图表快照'
    })
  }
  if (exportOptions.value.includeReasoningLogs) {
    const logs = Array.isArray(currentDiagnosis.value?.reasoningLogs) && currentDiagnosis.value.reasoningLogs.length
      ? currentDiagnosis.value.reasoningLogs
      : diagnosisProgress.logs || []
    if (logs.length) {
      article.blocks.push({ type: 'h2', text: `${romanSection(nextSection)}. GraphRAG 推理日志 (Reasoning Logs)` })
      article.blocks.push({
        type: 'table',
        headers: ['步骤 (Step)', '环节 (Stage)', '过程说明 (Detail)'].map(renderInlineMarkdown),
        rows: buildReportTableRows([], logs.map(log => [
          escapeHtml(`Step ${log.step ?? '-'}`),
          escapeHtml(log.title || '-'),
          escapeHtml(log.detail || '-')
        ]))
      })
      article.blocks.push({ type: 'caption', text: `表 ${romanSection(nextSection)}. GraphRAG 多跳推理过程日志` })
    }
  }
  return article
})

const buildReportTableRows = (headers, rows) => {
  const shouldMergeFirstColumn = headers.some(header => cleanInlineMarkdown(header).includes('一阶维度'))
  const normalizedRows = rows.map(row => row.map(cell => {
    if (cell && typeof cell === 'object' && Object.prototype.hasOwnProperty.call(cell, 'html')) {
      return { rowspan: 1, hidden: false, ...cell }
    }
    return { html: cell, rowspan: 1, hidden: false }
  }))
  if (!shouldMergeFirstColumn) {
    return normalizedRows.map(row => ({
      cells: row,
      visibleCells: row
    }))
  }

  let index = 0
  while (index < normalizedRows.length) {
    const current = normalizedRows[index][0]?.html || ''
    let span = 1
    while (index + span < normalizedRows.length && normalizedRows[index + span][0]?.html === current) {
      span += 1
    }
    normalizedRows[index][0].rowspan = span
    for (let offset = 1; offset < span; offset += 1) {
      normalizedRows[index + offset][0].hidden = true
    }
    index += span
  }
  return normalizedRows.map(row => ({
    cells: row,
    visibleCells: row.filter(cell => !cell.hidden)
  }))
}

const normalizedReportMarkers = computed(() => {
  const report = currentDiagnosis.value || {}
  const rows = rawDataRows.value
  const stats = report.statistics || {}
  const markerSource = Array.isArray(report.anomalyMarkers) && report.anomalyMarkers.length
    ? report.anomalyMarkers
    : Array.isArray(report.anomalies) ? report.anomalies : []
  const mapped = markerSource.map((item, index) => {
    const row = item.row && typeof item.row === 'object' ? item.row : rows[index] || {}
    const value = numericValue(item.value ?? item.metricValue ?? row[report.metricFieldLabel] ?? row[report.metricField])
    return {
      window: item.label || row[report.timeFieldLabel] || row[report.timeField] || `异常点 ${index + 1}`,
      value,
      reason: item.reason || item.description || deviationText(value, numericValue(stats.avg)),
      type: outlierType(value, numericValue(stats.avg))
    }
  })
  if (mapped.length) return mapped.slice(0, 5)
  if (!rows.length) return []
  return [...rows]
    .map((row, index) => ({
      window: row[report.timeFieldLabel] || row[report.timeField] || `样本 ${index + 1}`,
      value: numericValue(row[report.metricFieldLabel] ?? row[report.metricField]),
      reason: '',
      type: ''
    }))
    .filter(item => Number.isFinite(item.value))
    .sort((a, b) => Math.abs(b.value - numericValue(stats.avg)) - Math.abs(a.value - numericValue(stats.avg)))
    .slice(0, 3)
    .map(item => ({
      ...item,
      reason: deviationText(item.value, numericValue(stats.avg)),
      type: outlierType(item.value, numericValue(stats.avg))
    }))
})

const normalizedDimensionLabels = computed(() => {
  const report = currentDiagnosis.value || {}
  if (Array.isArray(report.dimensionFieldLabels) && report.dimensionFieldLabels.length) {
    return report.dimensionFieldLabels.filter(Boolean)
  }
  if (Array.isArray(report.dimensionFields) && report.dimensionFields.length) {
    return report.dimensionFields.map(field => report.fieldLabels?.[field] || field).filter(Boolean)
  }
  const rows = rawDataRows.value
  const first = rows.find(row => row && typeof row === 'object') || {}
  const metric = report.metricFieldLabel || report.metricField
  const time = report.timeFieldLabel || report.timeField
  return Object.keys(first).filter(key => key !== metric && key !== time && Number.isNaN(Number(first[key]))).slice(0, 3)
})

const buildAnomalyTableRows = (markers, stats) => {
  if (!markers.length) {
    return [['-', '-', '未发现 Z-Score 绝对值超过阈值的节点', 'Normal Observation'].map(renderInlineMarkdown)]
  }
  const avg = numericValue(stats.avg)
  return markers.map(item => [
    escapeHtml(item.window || '-'),
    formatReportValue(item.value),
    escapeHtml(item.reason || deviationText(item.value, avg)),
    escapeHtml(item.type || outlierType(item.value, avg))
  ])
}

const buildDimensionTableRows = (report, metricLabel) => {
  const contributionRows = []
  const contributions = Array.isArray(report.anomalyDimensionContributions) && report.anomalyDimensionContributions.length
    ? report.anomalyDimensionContributions
    : Array.isArray(report.dimensionContributions) ? report.dimensionContributions : []
  if (contributions.length) {
    contributions.slice(0, 3).forEach(contribution => {
      const dimension = readableFieldLabel(contribution.dimensionField || contribution.dimension, contribution.dimensionLabel || contribution.dimension, report.fieldLabels)
      const scope = contribution.scope || '全样本'
      ;(contribution.topItems || []).slice(0, 4).forEach(item => {
        contributionRows.push([
          escapeHtml(scope),
          escapeHtml(dimension),
          escapeHtml(readableValueLabel(item.name || item.label || '-', report.fieldLabels)),
          formatReportValue(item.value),
          `${formatPercent(item.share)}`
        ])
      })
    })
  }
  if (contributionRows.length) return contributionRows

  const rows = rawDataRows.value
  const dimensions = normalizedDimensionLabels.value
  dimensions.forEach(dimension => {
    const buckets = new Map()
    rows.forEach(row => {
      const name = String(row[dimension] ?? row[report.fieldLabels?.[dimension]] ?? '未分组')
      const value = numericValue(row[metricLabel] ?? row[report.metricFieldLabel] ?? row[report.metricField])
      buckets.set(name, (buckets.get(name) || 0) + value)
    })
    const total = [...buckets.values()].reduce((sum, value) => sum + value, 0)
    ;[...buckets.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .forEach(([name, value]) => {
        contributionRows.push([
          '全样本',
          escapeHtml(dimension),
          escapeHtml(name),
          formatReportValue(value),
          total ? `${(value / total * 100).toFixed(1)}%` : '0.0%'
        ])
      })
  })
  return contributionRows.length ? contributionRows : [[
    '未选择维度',
    '未选择维度',
    '-',
    '-',
    '-'
  ].map(renderInlineMarkdown)]
}

const buildEvidenceHtml = (report) => {
  const evidence = distinctEvidence(Array.isArray(report.docEvidence) ? report.docEvidence : [])
  if (!evidence.length) {
    return '<b>检索证据缺失声明 (Corpus Absence Note)：</b><br>在 RAG 检索阶段，未命中可引用的企业内部复盘文档或外部行业研报。当前得出的根因结论高度依赖于底层统计波动特征与图谱结构的内生字段关系。建议管理层后续向知识库补充非结构化业务说明，以提升归因模型的鲁棒性。'
  }
  const labels = [...new Set(evidence
    .map(item => displayEvidenceText(item.label || item.source || '知识文档'))
    .filter(Boolean))]
    .slice(0, 3)
    .map(label => `《${escapeHtml(label)}》`)
    .join('、')
  const text = evidence.map(item => displayEvidenceText(item.content || item.text || item.preview || '')).join(' ')
  const themes = []
  addEvidenceSignal(themes, text, /供应链|补货|SKU|库存|仓配/, '供应链与库存可得性')
  addEvidenceSignal(themes, text, /满减|促销|折扣|活动|大促|价格策略/, '促销退坡与价格策略变化')
  addEvidenceSignal(themes, text, /企业客户|审批|采购节奏|大客户/, '企业客户采购审批节奏')
  addEvidenceSignal(themes, text, /渠道|转化率|线上|直营|经销/, '渠道结构与转化率波动')
  addEvidenceSignal(themes, text, /物流|调拨|时效|退款|取消/, '物流履约与跨区调拨')
  const sourceText = labels || '企业内部复盘文档或外部行业研报'
  const themeText = themes.length ? themes.join('、') : '异常节点、维度贡献和图谱关系'
  return `<b>检索证据摘要 (Corpus Evidence Note)：</b><br>在 RAG 检索阶段，命中 ${evidence.length} 条企业内部复盘文档或外部行业研报，来源覆盖 ${sourceText}。证据主题集中在${escapeHtml(themeText)}，系统已将其纳入根因假设排序、置信度评估与建议动作生成。`
}

const buildHistoricalEvidenceHtml = (report) => {
  const histories = Array.isArray(report.historicalSimilarReports) ? report.historicalSimilarReports : []
  if (!histories.length) {
    return '<b>历史相似诊断召回：</b><br>当前未命中满足相似度阈值的历史诊断报告，后续报告累积后可用于异常模式复盘。'
  }
  const rows = histories.slice(0, 3).map(item => {
    const score = Number(item.score)
    const scoreText = Number.isFinite(score) ? score.toFixed(2) : '-'
    return `《${escapeHtml(item.title || '历史诊断报告')}》（相似度 ${scoreText}）：${escapeHtml(item.matchReason || '诊断上下文相近')}；历史根因：${escapeHtml(item.rootCause || '-')}`
  }).join('<br>')
  return `<b>历史相似诊断召回：</b><br>系统命中 ${histories.length} 份历史诊断报告，用于对比异常模式、根因结论和建议动作。<br>${rows}`
}

const parseReportBinding = (report = {}) => {
  const raw = report.bindingJson || report.binding
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch (error) {
    return {}
  }
}

const buildTraceabilityText = (report = {}) => {
  const binding = parseReportBinding(report)
  const snapshot = chartSnapshot.value || report.chartSnapshot || binding.chartSnapshot || {}
  const route = diagnosisBindingRoute(report) || 'diagnosis'
  const routeLabel = route === 'dashboard' ? '看板页面' : (route === 'chat' ? '对话查询页面' : '智能诊断报告页面')
  const tableName = report.tableName || binding.tableName || selectedTableName.value || '当前数据表'
  const chartType = snapshot.chartType ? chartTypeName(snapshot.chartType) : '诊断图表'
  const dashboardName = binding.dashboardName || snapshot.dashboardName || ''
  const cardTitle = binding.cardTitle || snapshot.cardTitle || snapshot.title || ''
  const rowCount = rawDataRows.value.length || (Array.isArray(binding.rawDataRows) ? binding.rawDataRows.length : 0)
  const sourceText = dashboardName || cardTitle
    ? `来源为「${[dashboardName, cardTitle].filter(Boolean).join(' / ')}」`
    : `来源为「${routeLabel}」`
  return `本报告已绑定原始数据表「${tableName}」、诊断生成时的图表快照和原始数据明细，图表类型为${chartType}，${sourceText}。当前报告保留 ${rowCount || 0} 条原始数据明细用于回溯。在线预览中点击图表快照或图表回溯入口，可定位至对应的${routeLabel}；导出的 PDF/Word 文件保留上述绑定信息，便于会议汇报和离线存档时追溯来源。`
}

const evidenceFingerprint = (item) => `${item.label || item.source || ''}|${String(item.content || item.text || '')
  .replace(/\s+/g, '')
  .replace(/[，。；：、,.!！?？#\-_*`~[\]()（）【】《》<>]/g, '')
  .slice(0, 80)}`

const distinctEvidence = (items) => {
  const seen = new Set()
  return items.filter(item => {
    const key = evidenceFingerprint(item)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

const readableFieldLabel = (field, explicitLabel, fieldLabels = {}) => {
  const label = String(explicitLabel || '').trim()
  if (label && !looksPhysicalField(label)) return label
  const mapped = String(fieldLabels?.[field] || '').trim()
  return mapped || String(field || '').trim() || '业务维度'
}

const readableValueLabel = (value, fieldLabels = {}) => {
  const text = String(value || '').trim()
  return fieldLabels?.[text] || text
}

const looksPhysicalField = (value) => /^col_\d{3}$/i.test(String(value || '').trim()) || String(value || '').trim() === 'sys_id'

const confidenceBandText = (rootCauses) => {
  if (!Array.isArray(rootCauses) || !rootCauses.length) return '低（Low）'
  const levels = [...new Set(rootCauses.map(item => String(item.level || '').toUpperCase()).filter(Boolean))]
  if (!levels.length) return '中（Medium）'
  return levels.map(level => {
    if (level === 'HIGH') return '高（High）'
    if (level === 'LOW') return '低（Low）'
    return '中（Medium）'
  }).join('、')
}

const fallbackReportSuggestions = (metricLabel, dimensions, timeLabel, report) => {
  const suggestions = []
  const markers = Array.isArray(report.anomalyMarkers) ? report.anomalyMarkers : []
  const evidence = Array.isArray(report.docEvidence) ? report.docEvidence : []
  if (markers.length) {
    suggestions.push(`复核异常节点对应的原始记录，确认「${metricLabel}」波动是否来自真实业务事件、统计口径变化或数据采集异常。`)
  } else {
    suggestions.push(`补充更长观测窗口或更高频明细数据后重新扫描「${metricLabel}」，避免样本不足导致异常判断不稳定。`)
  }
  if (Array.isArray(dimensions) && dimensions.length) {
    suggestions.push(`围绕「${dimensions.slice(0, 3).join('、')}」继续下钻到明细对象，验证头部贡献项是否集中放大指标波动。`)
  } else {
    suggestions.push(`补充可解释「${metricLabel}」变化的业务维度字段，用于生成可归因的贡献拆解。`)
  }
  if (timeLabel) {
    suggestions.push(`以「${timeLabel}」为轴对异常节点前后相邻窗口做对比，判断波动是短期脉冲还是趋势变化。`)
  }
  if (!evidence.length) {
    suggestions.push('上传企业复盘文档或行业研报并重新纳入 GraphRAG，以提升根因结论的外部证据支撑。')
  }
  return [...new Set(suggestions)].slice(0, 4)
}

const attributionListHtml = (cause, metricLabel) => {
  const confidence = formatConfidence(cause.confidence)
  const level = escapeHtml(cause.level || 'MEDIUM')
  const type = escapeHtml(cause.causeType || '根因假设')
  const impact = escapeHtml(cause.impactField || metricLabel)
  const evidence = escapeHtml(evidenceReportSummary(cause, metricLabel))
  return `<b>[置信度: ${confidence} / ${level}] ${type}：</b> 主要影响对象为「${impact}」。${evidence}`
}

const numericValue = (value) => {
  const number = Number(String(value ?? '').replace(/,/g, '').replace(/万$/, ''))
  if (String(value ?? '').includes('万') && Number.isFinite(number)) return number * 10000
  return Number.isFinite(number) ? number : 0
}

const formatReportValue = (value) => {
  const number = numericValue(value)
  return number.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatInteger = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? Math.round(number).toLocaleString('en-US') : '0'
}

const formatPercent = (value) => {
  let number = Number(value)
  if (!Number.isFinite(number)) return '0.0%'
  if (Math.abs(number) > 0 && Math.abs(number) <= 1) number *= 100
  return `${number.toFixed(1)}%`
}

const formatConfidence = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : '-'
}

const confidencePercent = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  const normalized = number <= 1 ? number * 100 : number
  return Math.max(0, Math.min(100, Math.round(normalized)))
}

const rootCauseLevelType = (level) => {
  const text = String(level || '').toUpperCase()
  if (text === 'HIGH') return 'danger'
  if (text === 'LOW') return 'info'
  return 'warning'
}

const deviationText = (value, avg) => {
  if (value > avg) return '处于样本总体极大值极点'
  if (value < avg) return '处于样本总体极小值极点'
  return '偏离均值，需结合标准差检验'
}

const outlierType = (value, avg) => {
  if (value > avg) return 'Positive Outlier (正向极值)'
  if (value < avg) return 'Negative Outlier (负向极值)'
  return 'Deviation Outlier (标准差偏离)'
}

const chartTypeName = (type) => {
  if (type === 'bar') return '柱状图'
  if (type === 'line') return '折线图'
  if (type === 'pie') return '饼图'
  return type || '图表'
}

const trimDisplay = (value, maxLength) => {
  const text = String(value || '')
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}…`
}

const graphNodeColor = (type) => {
  const text = String(type || '').toUpperCase()
  if (text.includes('DOC')) return '#16a34a'
  if (text.includes('ROOT')) return '#dc2626'
  if (text.includes('FIELD') || text.includes('METRIC')) return '#2563eb'
  if (text.includes('TABLE')) return '#7c3aed'
  return '#475569'
}

const isReportPersisted = computed(() => {
  const report = currentDiagnosis.value
  if (!report || typeof report !== 'object') return false
  if (typeof report.reportPersisted === 'boolean') return report.reportPersisted
  const persisted = report.reportPersistence?.persisted
  if (typeof persisted === 'boolean') return persisted
  return Boolean(report.id)
})

const persistFallbackReason = computed(() => {
  const report = currentDiagnosis.value
  const direct = String(report?.reportFallbackReason || '').trim()
  if (direct) return direct
  const nested = String(report?.reportPersistence?.error || '').trim()
  return nested || 'Neo4j 报告写入不可用'
})

const persistWarningTitle = computed(() =>
  `本次为降级结果：报告未写入 Neo4j，暂不支持历史回看与导出。原因：${persistFallbackReason.value}`
)

const graphRagRuntime = computed(() => currentDiagnosis.value?.graphRagRuntime || {})
const graphRagRuntimeMode = computed(() => String(graphRagRuntime.value?.mode || ''))
const graphRagRuntimeWarning = computed(() => {
  const runtime = graphRagRuntime.value || {}
  return `GraphRAG/Python AI 未成功启用，本次报告使用降级诊断生成。Neo4j=${runtime.neo4jEnabled ? '已启用' : '未启用'}，图节点=${runtime.graphNodeCount || 0}，图边=${runtime.graphEdgeCount || 0}，文档证据=${runtime.docEvidenceCount || 0}。`
})

const canExportDiagnosis = computed(() => Boolean(currentDiagnosis.value?.id && isReportPersisted.value))

const defaultDiagnosisForm = () => ({
  metricField: '',
  dimensionFields: [],
  timeField: '',
  detailLevel: 'detailed',
  anomalyType: 'fluctuation'
})

const defaultReportGenerateForm = () => ({
  detailLevel: 'detailed',
  anomalyType: 'fluctuation'
})

const defaultExportOptions = () => ({
  includeSnapshots: true,
  includeReasoningLogs: true,
  enablePdfEncryption: false
})

const runDiagnosisWithDetail = async () => {
  if (!selectedTableName.value) {
    ElMessage.warning('请先选择诊断数据表')
    return
  }
  if (!diagnosisForm.value.metricField) {
    ElMessage.warning('请选择指标字段')
    return
  }
  diagnosisForm.value.detailLevel = reportGenerateForm.value.detailLevel
  diagnosisForm.value.anomalyType = reportGenerateForm.value.anomalyType
  diagnosisLoading.value = true
  currentDiagnosis.value = null
  diagnosisProgress.value = {
    percentage: 3,
    step: '任务接收',
    logs: [{ step: 0, title: '任务接收', status: 'running', detail: '诊断报告生成请求已提交，等待后端确认。' }]
  }
  try {
    const result = await runDiagnosisReportStream({
      tableName: selectedTableName.value,
      metricField: diagnosisForm.value.metricField,
      dimensionFields: diagnosisForm.value.dimensionFields,
      timeField: diagnosisForm.value.timeField || null,
      detailLevel: diagnosisForm.value.detailLevel || 'detailed',
      anomalyType: diagnosisForm.value.anomalyType || 'fluctuation'
    })
    if (!result) throw new Error('诊断报告生成完成事件缺失')
    currentDiagnosis.value = result
    applyDiagnosisStreamProgress({
      percentage: 100,
      step: '报告生成',
      log: { step: 9, title: '报告生成', status: 'completed', detail: result.reportPersisted === false ? '诊断结果已生成（降级模式，未写入 Neo4j）。' : '诊断报告已生成并写入 Neo4j。' }
    })
    await loadDiagnosisReports()
    ElMessage.success(result.reportPersisted === false ? '诊断结果已生成（降级模式）' : '诊断报告已生成')
  } catch (error) {
    ElMessage.error(error?.message || '诊断报告生成失败')
  } finally {
    diagnosisLoading.value = false
  }
}

const runDiagnosisReportStream = async (payload) => {
  const token = readDiagnosisAuthToken()
  const response = await fetch(`${API_BASE}/api/diagnosis/run-stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload)
  })
  if (!response.ok || !response.body) {
    throw new Error(`诊断流式接口请求失败：${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let result = null
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\n\n/)
    buffer = frames.pop() || ''
    for (const frame of frames) {
      const parsed = parseDiagnosisStreamFrame(frame)
      if (!parsed) continue
      if (parsed.event === 'progress') {
        applyDiagnosisStreamProgress(parsed.data)
      } else if (parsed.event === 'result') {
        result = parsed.data
      } else if (['complete', 'completed', 'done', 'FINISHED'].includes(parsed.event)) {
        result = parsed.data?.result || parsed.data || result
      } else if (parsed.event === 'error') {
        throw new Error(parsed.data?.message || '诊断报告生成失败')
      }
    }
  }
  if (buffer.trim()) {
    const parsed = parseDiagnosisStreamFrame(buffer)
    if (parsed?.event === 'result') result = parsed.data
    if (['complete', 'completed', 'done', 'FINISHED'].includes(parsed?.event)) {
      result = parsed.data?.result || parsed.data || result
    }
    if (parsed?.event === 'error') throw new Error(parsed.data?.message || '诊断报告生成失败')
  }
  return result
}

const parseDiagnosisStreamFrame = (frame) => {
  const lines = String(frame || '').split(/\r?\n/)
  let event = 'message'
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return null
  try {
    return { event, data: JSON.parse(dataLines.join('\n')) }
  } catch (error) {
    return null
  }
}

const applyDiagnosisStreamProgress = (event = {}) => {
  const nextLog = event.log && typeof event.log === 'object' ? event.log : null
  const logs = Array.isArray(diagnosisProgress.value?.logs) ? [...diagnosisProgress.value.logs] : []
  if (nextLog) {
    const key = `${nextLog.step ?? ''}-${nextLog.title ?? ''}-${nextLog.detail ?? ''}`
    const exists = logs.some(item => `${item.step ?? ''}-${item.title ?? ''}-${item.detail ?? ''}` === key)
    if (!exists) logs.push(nextLog)
  }
  diagnosisProgress.value = {
    percentage: Math.max(Number(diagnosisProgress.value?.percentage || 0), Number(event.percentage || 0)),
    step: event.step || diagnosisProgress.value?.step || '诊断中',
    logs
  }
}

const readDiagnosisAuthToken = () => {
  const token = localStorage.getItem('token')
  if (token) return token
  try {
    return JSON.parse(localStorage.getItem('insight_auth') || 'null')?.token || ''
  } catch (error) {
    return ''
  }
}

const resetDiagnosisWorkspace = () => {
  selectedTableName.value = ''
  diagnosisForm.value = defaultDiagnosisForm()
  reportGenerateForm.value = defaultReportGenerateForm()
  exportOptions.value = defaultExportOptions()
  knowledgeDocFile.value = null
  knowledgeDocFiles.value = []
  knowledgeDocUploadFiles.value = []
  knowledgeDocs.value = []
  currentDiagnosis.value = null
  detailDialogVisible.value = false
  detailDialogType.value = 'evidence'
  previewFullscreenVisible.value = false
  diagnosisProgress.value = {
    percentage: 0,
    step: '待开始',
    logs: []
  }
}

const exportDiagnosisReportWithOptions = async (format, overrides = {}) => {
  if (!currentDiagnosis.value) {
    ElMessage.warning('请先生成或选择一份诊断报告')
    return
  }
  await nextTick()
  const payload = {
    includeSnapshots: overrides.includeSnapshots ?? exportOptions.value.includeSnapshots,
    includeReasoningLogs: overrides.includeReasoningLogs ?? exportOptions.value.includeReasoningLogs,
    enablePdfEncryption: format === 'pdf' && (overrides.enablePdfEncryption ?? exportOptions.value.enablePdfEncryption)
  }
  if (format === 'pdf') {
    await exportPreviewPdf(payload)
    return
  }
  await exportDiagnosisReport(format, payload)
}

const exportPreviewPdf = async (options = {}) => {
  const el = exportPaperRef.value || reportPaperRef.value
  if (!el) return ElMessage.error('报告预览尚未渲染完成，无法导出 PDF')
  const pdf = new jsPDF({
    orientation: 'p',
    unit: 'mm',
    format: 'a4'
  })
  const pageWidth = pdf.internal.pageSize.getWidth()
  const pageHeight = pdf.internal.pageSize.getHeight()
  const { container: exportContainer, dispose } = createPaginatedExportPages(el)
  try {
    const pages = Array.from(exportContainer.querySelectorAll('.paper'))
    for (let index = 0; index < pages.length; index += 1) {
      const canvas = await html2canvas(pages[index], {
        backgroundColor: '#ffffff',
        scale: Math.max(3, Math.min(4, (window.devicePixelRatio || 1) * 2)),
        useCORS: true
      })
      if (index > 0) pdf.addPage()
      pdf.addImage(canvas.toDataURL('image/png'), 'PNG', 0, 0, pageWidth, pageHeight)
      canvas.width = 0
      canvas.height = 0
    }
  } finally {
    dispose()
  }
  const filename = `${reportExportBaseName()}.pdf`
  const blob = pdf.output('blob')
  if (options.enablePdfEncryption) {
    await downloadEncryptedPreviewPdf(blob, filename)
    return
  }
  downloadBlob(blob, filename)
}

const downloadEncryptedPreviewPdf = async (blob, filename) => {
  const formData = new FormData()
  formData.append('file', blob, filename)
  formData.append('filename', filename)
  const loading = ElLoading.service({
    lock: true,
    text: '正在加密 PDF...',
    background: 'rgba(255, 255, 255, 0.65)'
  })
  try {
    const response = await http.post('/api/diagnosis/encrypt-pdf', formData, {
      responseType: 'blob',
      timeout: 180000
    })
    const encryptedBlob = new Blob([response.data], { type: response.headers['content-type'] || 'application/pdf' })
    await ensurePdfEncrypted(encryptedBlob)
    loading.close()
    downloadBlob(encryptedBlob, resolveDownloadFilename(response.headers['content-disposition'], 'pdf') || filename)
    ElMessage.success('PDF 已加密导出，打开密码：insight-spark')
  } catch (error) {
    loading.close()
    ElMessage.error(await resolvePdfExportError(error))
  }
}

const ensurePdfEncrypted = async (blob) => {
  if ((blob.type || '').includes('application/json')) {
    const text = await blob.text()
    const message = JSON.parse(text || '{}')?.message || text
    throw new Error(message || 'PDF 加密接口返回异常')
  }
  const bytes = new Uint8Array(await blob.arrayBuffer())
  if (!containsAsciiToken(bytes, '/Encrypt')) {
    throw new Error('PDF 加密失败：后端返回的文件未包含加密信息，已取消下载')
  }
}

const containsAsciiToken = (bytes, token) => {
  const pattern = Array.from(token).map(char => char.charCodeAt(0))
  outer:
  for (let i = 0; i <= bytes.length - pattern.length; i += 1) {
    for (let j = 0; j < pattern.length; j += 1) {
      if (bytes[i + j] !== pattern[j]) continue outer
    }
    return true
  }
  return false
}

const resolvePdfExportError = async (error) => {
  const data = error?.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      return JSON.parse(text || '{}')?.message || text || 'PDF 加密导出失败'
    } catch (parseError) {
      return 'PDF 加密导出失败'
    }
  }
  return error?.message || 'PDF 加密导出失败'
}

const resolveDownloadFilename = (contentDisposition, format) => {
  const fallback = `智能诊断报告.${format === 'word' ? 'docx' : format}`
  if (!contentDisposition) return fallback
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) return decodeURIComponent(utf8Match[1])
  const asciiMatch = contentDisposition.match(/filename="?([^"]+)"?/i)
  return asciiMatch?.[1] || fallback
}

const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const createPaginatedExportPages = (sourceEl) => {
  const style = document.createElement('style')
  style.textContent = reportExportCss('.pdf-page-export')
  document.head.appendChild(style)
  const container = document.createElement('div')
  container.className = 'pdf-page-export'
  container.style.cssText = 'position:fixed;left:-12000px;top:0;width:210mm;background:#fff;z-index:-1;opacity:1;pointer-events:none;'
  document.body.appendChild(container)

  let currentPage = createExportPage(container)
  const sourceChildren = Array.from(sourceEl.children)
  const isOverflow = (page) => page.scrollHeight > page.clientHeight + 2
  const appendBlock = (sourceNode) => {
    if (sourceNode.tagName === 'UL') {
      appendList(sourceNode)
      return
    }
    if (sourceNode.tagName === 'TABLE') {
      appendTable(sourceNode)
      return
    }
    const clone = sourceNode.cloneNode(true)
    currentPage.appendChild(clone)
    if (isOverflow(currentPage) && currentPage.children.length > 1) {
      currentPage.removeChild(clone)
      currentPage = createExportPage(container)
      currentPage.appendChild(clone)
    }
  }
  const appendList = (sourceList) => {
    let list = sourceList.cloneNode(false)
    currentPage.appendChild(list)
    for (const item of Array.from(sourceList.children)) {
      const clone = item.cloneNode(true)
      list.appendChild(clone)
      if (isOverflow(currentPage)) {
        list.removeChild(clone)
        if (!list.children.length) currentPage.removeChild(list)
        currentPage = createExportPage(container)
        list = sourceList.cloneNode(false)
        currentPage.appendChild(list)
        list.appendChild(clone)
      }
    }
  }
  const appendTable = (sourceTable) => {
    const fullTable = sourceTable.cloneNode(true)
    currentPage.appendChild(fullTable)
    if (!isOverflow(currentPage)) return
    currentPage.removeChild(fullTable)
    if (currentPage.children.length) {
      currentPage = createExportPage(container)
      currentPage.appendChild(fullTable)
      if (!isOverflow(currentPage)) return
      currentPage.removeChild(fullTable)
    }

    let table = createTableShell(sourceTable)
    currentPage.appendChild(table.el)
    for (const row of Array.from(sourceTable.querySelectorAll('tbody tr'))) {
      const clone = row.cloneNode(true)
      table.body.appendChild(clone)
      if (isOverflow(currentPage)) {
        table.body.removeChild(clone)
        if (!table.body.children.length) currentPage.removeChild(table.el)
        currentPage = createExportPage(container)
        table = createTableShell(sourceTable)
        currentPage.appendChild(table.el)
        table.body.appendChild(clone)
      }
    }
  }
  sourceChildren.forEach(appendBlock)
  return {
    container,
    dispose: () => {
      container.remove()
      style.remove()
    }
  }
}

const createExportPage = (container) => {
  const page = document.createElement('div')
  page.className = 'paper'
  page.style.cssText = 'width:210mm;height:297mm;min-height:0;margin:0;padding:25.4mm 31.8mm;box-sizing:border-box;background:#fff;box-shadow:none;overflow:hidden;'
  container.appendChild(page)
  return page
}

const createTableShell = (sourceTable) => {
  const table = sourceTable.cloneNode(false)
  const thead = sourceTable.querySelector('thead')?.cloneNode(true)
  const tbody = document.createElement('tbody')
  if (thead) table.appendChild(thead)
  table.appendChild(tbody)
  return { el: table, body: tbody }
}

const reportExportBaseName = () => {
  return '智能诊断报告'
}

const reportExportCss = (scope = '') => {
  const selector = (value) => scope ? `${scope} ${value}` : value
  const paperRule = scope
    ? 'background-color: #fff; width: 210mm; min-height: 297mm; margin: 0 auto; padding: 25.4mm 31.8mm; box-shadow: none; box-sizing: border-box; text-align: justify; color: #000; font-family: "Times New Roman", STSong, "SimSun", serif; line-height: 1.6; -webkit-print-color-adjust: exact; print-color-adjust: exact;'
    : 'background-color: #fff; max-width: none; min-height: 0; margin: 0; padding: 0; box-shadow: none; box-sizing: border-box; text-align: justify; color: #000; font-family: "Times New Roman", STSong, "SimSun", serif; line-height: 1.6;'
  return `
${scope ? '' : '@page { size: A4; margin: 25.4mm 31.8mm; }'}
${scope ? '' : 'body { background-color: #fff; margin: 0; padding: 0; color: #000; font-family: "Times New Roman", STSong, "SimSun", serif; line-height: 1.6; }'}
${selector('.paper')} { ${paperRule} }
${selector('.header')} { text-align: center; margin-bottom: 40px; border-bottom: 1px solid #000; padding-bottom: 20px; }
${selector('.doc-type')} { font-family: Arial, Helvetica, sans-serif; font-size: 10px; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 15px; }
${selector('.title')} { font-size: 22px; font-weight: bold; margin-bottom: 10px; line-height: 1.4; }
${selector('.subtitle')} { font-size: 14px; font-style: italic; margin-bottom: 20px; }
${selector('.authors')} { font-size: 12px; font-family: Arial, Helvetica, sans-serif; }
${selector('.abstract')} { margin: 0 20px 30px 20px; font-size: 12px; line-height: 1.5; }
${selector('.abstract-title')} { font-weight: bold; font-family: Arial, Helvetica, sans-serif; font-size: 12px; }
${selector('h2')} { font-family: Arial, Helvetica, sans-serif; font-size: 14px; font-weight: bold; margin: 30px 0 10px; text-transform: uppercase; }
${selector('h3')} { margin: 14px 0 8px; color: #000; font-family: Arial, Helvetica, sans-serif; font-size: 12px; font-weight: 700; }
${selector('p')} { font-size: 12px; margin: 0 0 12px; text-indent: 2em; }
${selector('table')} { width: 100%; border-collapse: collapse; margin: 20px 0 8px; font-size: 11px; font-family: "Times New Roman", STSong, "SimSun", serif; border-top: 2px solid #000; border-bottom: 2px solid #000; page-break-inside: avoid; }
${selector('th')}, ${selector('td')} { text-align: center; padding: 8px 4px; vertical-align: middle; overflow-wrap: anywhere; border-left: 0; border-right: 0; }
${selector('th')} { border-top: 0; border-bottom: 1px solid #000; font-weight: bold; }
${selector('td')} { border-top: 0; border-bottom: 1px dashed #ccc; }
${selector('tbody tr:last-child td')} { border-bottom: 0; }
${selector('td.group-cell')} { border-bottom: 1px dashed #ccc; }
${selector('ul')}, ${selector('.report-list')} { font-size: 12px; padding-left: 40px; margin: 0 0 20px; }
${selector('li')}, ${selector('.report-list li')} { margin-bottom: 6px; }
${selector('h2')}, ${selector('h3')}, ${selector('p')}, ${selector('li')}, ${selector('.abstract')}, ${selector('.evidence-block')}, ${selector('.figure-caption')} { page-break-inside: avoid; break-inside: avoid; }
${selector('.math')} { font-family: "Times New Roman", STSong, "SimSun", serif; font-style: italic; }
${selector('.code-inline')}, ${selector('code')} { font-family: "Courier New", Courier, monospace; font-size: 11px; }
${selector('.figure-caption')} { text-align: center; font-size: 11px; font-weight: bold; margin-top: 10px; margin-bottom: 20px; }
${selector('.evidence-block')} { border-left: 2px solid #000; padding-left: 15px; margin: 15px 0; font-size: 11px; color: #333; }
${selector('.report-image-block')} { margin: 16px 0 20px; border: 1px solid #dbe3ef; background: #fff; page-break-inside: avoid; break-inside: avoid; }
${selector('.snapshot-titlebar')} { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
${selector('.snapshot-titlebar strong')} { display: block; color: #0f172a; font-family: Arial, Helvetica, sans-serif; font-size: 12px; }
${selector('.snapshot-titlebar span')} { display: block; margin-top: 2px; color: #64748b; font-family: Arial, Helvetica, sans-serif; font-size: 10px; }
${selector('.snapshot-image-box')} { min-height: 0; display: grid; place-items: center; padding: 14px; background: #fff; }
${selector('.snapshot-image-box img')} { display: block; width: 100%; max-height: 95mm; object-fit: contain; border: 1px solid #eef2f7; background: #fff; }
${selector('.report-spacer')} { height: 8px; }
`
}

const bindBackToSource = (row) => {
  restoreDiagnosisBinding(row)
  ElMessage.success(`已定位到报告《${row.title || row.id}》绑定的原始分析页面`)
}

const previewHistoryReport = async (row) => {
  await loadDiagnosisReportDetail(row)
  previewFullscreenVisible.value = true
}

const exportHistoryReport = async (row, format = 'pdf') => {
  await loadDiagnosisReportDetail(row)
  await nextTick()
  await exportDiagnosisReportWithOptions(format, format === 'pdf' ? { enablePdfEncryption: true } : {})
}

const confirmDeleteReports = async (rows) => {
  const reportRows = Array.isArray(rows) ? rows : []
  const ids = [...new Set(reportRows
    .map(row => Number(row?.id))
    .filter(id => Number.isFinite(id) && id > 0))]
  if (!ids.length) {
    ElMessage.warning('请先选择要删除的诊断报告')
    return
  }
  const title = ids.length === 1
    ? `确认删除诊断报告《${reportRows[0]?.title || ids[0]}》？删除后不可恢复。`
    : `确认删除选中的 ${ids.length} 份诊断报告？删除后不可恢复。`
  try {
    await ElMessageBox.confirm(title, '删除诊断报告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await deleteDiagnosisReports(ids)
    selectedReportRows.value = []
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return
    }
    ElMessage.error(error?.message || '删除诊断报告失败')
  }
}

const openRegenerateDialog = (row) => {
  regenerateTarget.value = row
  regenerateForm.value = {
    detailLevel: row.detailLevel || 'detailed',
    anomalyType: row.anomalyType || 'fluctuation'
  }
  regenerateDialogVisible.value = true
}

const confirmRegenerateReport = async () => {
  if (!regenerateTarget.value) return
  await regenerateDiagnosisReport(regenerateTarget.value, regenerateForm.value)
  regenerateDialogVisible.value = false
}
</script>

<style scoped>
.generator-form {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px 16px;
}

.diagnosis-layout {
  display: grid;
  grid-template-columns: minmax(410px, 0.72fr) minmax(760px, 1.55fr);
  align-items: stretch;
  grid-auto-rows: auto;
  gap: 10px;
  min-height: calc(100vh - 94px);
}

.diagnosis-toolbar {
  grid-column: 1 / -1;
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 10px 14px;
  border: 1px solid #e5edf7;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}

.toolbar-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 14px;
}

.toolbar-title h1 {
  margin: 0;
  color: #0f2347;
  font-size: 22px;
  font-weight: 800;
  line-height: 1.2;
}

.toolbar-title p {
  margin: 4px 0 0;
  color: #77869b;
  font-size: 12px;
  font-weight: 600;
}

.toolbar-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-actions :deep(.el-button) {
  height: 36px;
  min-width: 110px;
  margin-left: 0;
  border-radius: 5px;
  font-weight: 700;
}

.toolbar-actions :deep(.el-button--primary) {
  min-width: 126px;
  background: #1677ff;
  border-color: #1677ff;
}

.diagnosis-layout :deep(.panel) {
  border-color: #dce5f2;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.05);
}

.report-generator-panel,
.diagnosis-result,
.graph-rag-progress-panel,
.report-history {
  padding: 16px 18px;
}

.report-generator-panel {
  grid-column: 1;
  grid-row: 2;
  align-self: stretch;
  overflow: visible;
}

.diagnosis-result {
  grid-column: 2;
  grid-row: 2;
  align-self: stretch;
  display: block;
  overflow: visible;
}

.graph-rag-progress-panel {
  grid-column: 1 / -1;
  grid-row: 3;
  position: relative;
  z-index: 0;
}

.report-history {
  grid-column: 1 / -1;
  grid-row: 4;
  position: relative;
  z-index: 0;
}

.panel-header {
  margin-bottom: 10px;
}

.panel-header h2 {
  color: #0f2347;
  font-size: 17px;
  font-weight: 800;
  line-height: 1.25;
}

.panel-header p {
  max-width: 760px;
  color: #62728a;
  font-size: 12px;
  line-height: 1.5;
}

.config-field,
.full-row-field {
  min-width: 0;
}

.form-block {
  display: block;
}

.config-field {
  flex: 0 0 calc(50% - 8px);
  width: calc(50% - 8px);
}

.full-row-field {
  flex: 0 0 100%;
  width: 100%;
}

.form-block-label {
  display: block;
  margin-bottom: 6px;
  color: #1e2f4d;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.35;
}

.report-generator-panel :deep(.el-input__wrapper),
.report-generator-panel :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 5px;
  box-shadow: 0 0 0 1px #d8e1ee inset;
}

.report-detail-toggle {
  min-height: 36px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.report-detail-toggle :deep(.el-radio) {
  margin-right: 0;
  color: #51637d;
  font-weight: 500;
}

.generator-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.generator-actions :deep(.el-button) {
  min-width: 118px;
  border-radius: 5px;
}

.generation-progress {
  margin-top: 14px;
  border: 0;
  border-radius: 0;
  padding: 4px 0 0;
  background: transparent;
}

.empty-report-state {
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, 0.75fr) minmax(360px, 1.25fr);
  align-items: center;
  justify-content: center;
  gap: 46px;
  padding: 22px 60px 34px;
  overflow: visible;
}

.report-content-body {
  min-width: 0;
  overflow: visible;
}

.empty-report-visual {
  display: flex;
  justify-content: flex-end;
}

.report-illustration {
  position: relative;
  width: 154px;
  height: 176px;
  padding: 26px 24px;
  border: 1px solid #e2ebf8;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #eff6ff 100%);
  box-shadow: 0 14px 34px rgba(37, 99, 235, 0.16);
}

.illustration-line {
  display: block;
  width: 82px;
  height: 6px;
  margin-bottom: 13px;
  border-radius: 999px;
  background: #d9e8ff;
}

.illustration-line.line-short {
  width: 54px;
}

.illustration-line.line-mid {
  width: 104px;
}

.illustration-bars {
  position: absolute;
  left: 24px;
  bottom: 22px;
  display: flex;
  align-items: flex-end;
  gap: 9px;
}

.illustration-bars i {
  width: 14px;
  border-radius: 4px 4px 0 0;
  background: #9bc5ff;
}

.illustration-bars i:nth-child(1) {
  height: 31px;
}

.illustration-bars i:nth-child(2) {
  height: 52px;
}

.illustration-bars i:nth-child(3) {
  height: 78px;
}

.illustration-chart {
  position: absolute;
  right: -28px;
  bottom: 12px;
  width: 62px;
  height: 62px;
  border: 9px solid #5b9dff;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.72);
}

.illustration-chart::after {
  position: absolute;
  right: -23px;
  bottom: -15px;
  width: 36px;
  height: 9px;
  border-radius: 999px;
  background: #2f78e8;
  transform: rotate(45deg);
  content: '';
}

.empty-report-copy h3 {
  margin: 0;
  color: #0f2347;
  font-size: 18px;
  font-weight: 800;
}

.empty-report-copy p {
  margin: 12px 0 16px;
  color: #65748c;
  font-size: 12px;
}

.empty-feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  gap: 12px 36px;
}

.empty-feature-grid span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #536782;
  font-size: 12px;
  font-weight: 600;
}

.empty-feature-grid .el-icon {
  width: 17px;
  height: 17px;
  border-radius: 50%;
  color: #2f7df6;
  background: #e9f2ff;
}

.empty-generate-button {
  min-width: 138px;
  margin-top: 22px;
}

.empty-report-copy small {
  display: block;
  margin-top: 10px;
  color: #8a9ab3;
  font-size: 12px;
}

.progress-panel-title h2 {
  margin: 0 0 10px;
  color: #0f2347;
  font-size: 16px;
  font-weight: 800;
}

.graph-progress-flow {
  display: grid;
  grid-template-columns:
    minmax(112px, max-content) minmax(72px, 1fr)
    minmax(132px, max-content) minmax(72px, 1fr)
    minmax(112px, max-content) minmax(72px, 1fr)
    minmax(112px, max-content) minmax(72px, 1fr)
    minmax(112px, max-content) minmax(170px, 0.9fr);
  align-items: center;
  column-gap: 14px;
  min-width: 0;
  overflow: hidden;
}

.graph-progress-node {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.graph-progress-node > div:last-child {
  min-width: 0;
}

.graph-progress-connector {
  width: 100%;
  border-top: 1px dashed #bfd0e7;
}

.graph-progress-connector.active {
  border-top-color: #5b9dff;
}

.node-icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border: 1px solid #d8e2ef;
  border-radius: 50%;
  color: #8da0b8;
  background: #f8fbff;
  box-shadow: 0 4px 10px rgba(15, 23, 42, 0.05);
}

.graph-progress-node.active .node-icon,
.graph-progress-node.current .node-icon {
  color: #2678ff;
  border-color: #b7d3ff;
  background: #ecf5ff;
}

.graph-progress-node strong {
  display: block;
  color: #213654;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.graph-progress-node span {
  display: block;
  margin-top: 4px;
  color: #8a9ab3;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.graph-progress-status {
  min-height: 76px;
  padding: 12px 14px;
  border-radius: 8px;
  background: linear-gradient(180deg, #f8fbff 0%, #f3f7fc 100%);
  min-width: 0;
}

.graph-progress-status span,
.graph-progress-status small {
  display: block;
  color: #7b8ca5;
  font-size: 12px;
}

.graph-progress-status strong {
  display: block;
  margin: 7px 0;
  color: #2578ff;
  font-size: 12px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  color: #1f2a44;
  font-size: 12px;
  margin-bottom: 8px;
}

.progress-steps {
  position: relative;
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  color: #71809a;
  font-size: 11px;
  text-align: center;
}

.progress-steps .active {
  color: #2563eb;
  font-weight: 700;
}

.history-actions {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: 180px 180px minmax(240px, 1fr) 86px;
  align-items: center;
  gap: 12px;
  justify-content: stretch;
}

.history-actions :deep(.el-button) {
  width: 100%;
  min-width: 0;
  padding: 0 9px;
  margin-left: 0;
  border-radius: 5px;
  font-size: 12px;
}

.history-actions :deep(.el-input__wrapper),
.history-actions :deep(.el-select__wrapper) {
  min-height: 34px;
}

.history-actions :deep(.el-select__placeholder),
.history-actions :deep(.el-button span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-history-header {
  align-items: flex-start;
  flex-wrap: nowrap;
}

.report-history-copy {
  flex: 0 0 auto;
  min-width: 0;
}

.report-history-copy h2,
.report-history-copy p {
  word-break: normal;
  overflow-wrap: normal;
}

.history-filter {
  width: 100%;
  min-width: 0;
}

.history-search {
  width: 100%;
}

.report-history :deep(.el-table) {
  width: 100%;
  font-size: 12px;
  table-layout: fixed;
}

.report-history :deep(.el-table .cell) {
  padding: 0 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-history :deep(.el-table__row) {
  height: 40px;
}

.report-history :deep(.el-table th.el-table__cell) {
  background: #f7faff;
  color: #20365c;
  font-weight: 700;
}

.report-history :deep(.el-table__header),
.report-history :deep(.el-table__body) {
  width: 100% !important;
}

.report-history-table {
  margin-top: 10px;
}

.history-row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.history-row-actions :deep(.el-button) {
  width: auto;
  height: 24px;
  padding: 0;
  margin-left: 0;
  flex: 0 0 auto;
}

.history-row-actions :deep(.el-icon) {
  font-size: 14px;
}

.history-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  color: #62728a;
  font-size: 12px;
}

@media (max-width: 760px) {
  .diagnosis-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .history-actions {
    grid-template-columns: minmax(96px, 1fr) minmax(96px, 1fr) 86px 58px;
    gap: 5px;
  }

  .history-actions .el-button {
    margin-left: 0;
  }
}

.full-preview {
  max-height: 72vh;
}

.report-preview-shell {
  overflow: auto;
  padding: 0 12px 24px;
  background: #525659;
}

.offscreen-report-export {
  position: fixed;
  left: -10000px;
  top: 0;
  width: 900px;
  pointer-events: none;
  opacity: 0;
  z-index: -1;
}

.offscreen-report-export .report-reader {
  padding: 0;
}

.preview-cover {
  padding: 34px 38px;
  color: #fff;
  background: linear-gradient(135deg, #0f172a, #1d4ed8);
  border-radius: 10px;
}

.preview-cover span {
  color: #bfdbfe;
  font-size: 13px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.preview-cover h1 {
  margin: 12px 0;
  font-size: 28px;
}

.preview-cover p {
  max-width: 760px;
  margin: 0;
  color: #dbeafe;
  line-height: 1.7;
}

.preview-section {
  margin-top: 16px;
  padding: 20px 24px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}

.preview-section h2 {
  margin: 0 0 14px;
  color: #0f172a;
  font-size: 18px;
}

.export-options {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  color: #214068;
  font-size: 12px;
}

.export-options :deep(.el-checkbox) {
  height: 22px;
  margin-right: 0;
  color: #536782;
  font-size: 12px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
}

.export-options :deep(.el-checkbox__input) {
  height: 14px;
  line-height: 14px;
}

.export-options :deep(.el-checkbox__inner) {
  width: 15px;
  height: 15px;
  border-radius: 3px;
  border-color: #b7c4d6;
}

.export-options :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: #2878ff;
  border-color: #2878ff;
}

.export-options :deep(.el-checkbox__inner::after) {
  box-sizing: content-box;
  left: 5px;
  top: 2px;
  width: 3px;
  height: 7px;
  border: 2px solid #fff;
  border-left: 0;
  border-top: 0;
  transform: rotate(45deg) scaleY(1);
  transform-origin: center;
}

.export-options :deep(.el-checkbox__label) {
  padding-left: 7px;
  color: #536782;
  font-size: 12px;
}

.pdf-encryption-row {
  min-height: 32px;
  display: flex;
  align-items: center;
  gap: 9px;
  color: #536782;
  font-size: 12px;
  flex-wrap: wrap;
}

.pdf-encryption-row :deep(.el-switch) {
  --el-switch-on-color: #2878ff;
  --el-switch-off-color: #c7d0dd;
  height: 18px;
  line-height: 18px;
}

.pdf-encryption-row :deep(.el-switch__core) {
  min-width: 32px;
  width: 32px;
  height: 16px;
  border-radius: 999px;
}

.pdf-encryption-row :deep(.el-switch__core .el-switch__action) {
  width: 12px;
  height: 12px;
}

.pdf-encryption-row span {
  color: #536782;
  font-size: 12px;
  line-height: 20px;
}

.field-label-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.knowledge-upload-panel {
  width: 100%;
  min-width: 0;
}

.knowledge-upload-row {
  display: block;
  gap: 10px;
  align-items: center;
}

.knowledge-pick-button {
  width: 100%;
  height: 40px;
}

.knowledge-upload-row :deep(.el-upload) {
  width: 100%;
}

.knowledge-upload-row :deep(.el-upload-list) {
  width: 100%;
  margin-top: 8px;
}

.knowledge-upload-dropzone {
  display: grid;
  place-items: center;
  gap: 5px;
  width: 100%;
  box-sizing: border-box;
  min-height: 68px;
  padding: 10px 12px;
  border: 1px dashed #b9c9df;
  border-radius: 5px;
  background: #fcfdff;
  color: #47607d;
  font-size: 12px;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease;
}

.knowledge-upload-dropzone:hover,
.knowledge-upload-dropzone.is-uploading {
  border-color: #2878ff;
  background: #f5f9ff;
  color: #1f6fff;
}

.knowledge-upload-dropzone .el-icon {
  color: #6d91c6;
  font-size: 18px;
}

.knowledge-upload-dropzone small {
  color: #8a9ab3;
  font-size: 11px;
}

.knowledge-file-note {
  display: block;
  margin-top: 7px;
  color: #475569;
  font-size: 12px;
}

.knowledge-file-note span {
  color: #475569;
  line-height: 1.45;
}

.knowledge-doc-list {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}

.knowledge-doc-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #334155;
  font-size: 13px;
}

.knowledge-doc-item small {
  flex: 0 0 auto;
  color: #64748b;
}

.reasoning-timeline {
  margin: 12px 0;
}

.reasoning-timeline p {
  margin: 4px 0 0;
}

.report-section {
  margin-top: 8px;
  padding: 12px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: #fff;
  min-width: 0;
  overflow: visible;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.section-heading h3 {
  margin: 0;
  color: #0f2347;
  font-size: 15px;
  font-weight: 800;
}

.section-heading p {
  margin: 3px 0 0;
  color: #6c7a91;
  font-size: 12px;
}

.metric-card-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.metric-card {
  min-width: 0;
  min-height: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdff 0%, #f6f9fe 100%);
}

.metric-card span {
  display: block;
  color: #63738b;
  font-size: 12px;
}

.metric-card strong {
  display: block;
  margin-top: 6px;
  color: #102247;
  font-size: 20px;
  line-height: 1.1;
  overflow-wrap: anywhere;
}

.metric-card-icon {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  color: #3f7cff;
  font-size: 20px;
  background: #edf4ff;
}

.metric-card.is-warning .metric-card-icon {
  color: #ff5f6f;
  background: #fff1f3;
}

.metric-card.is-graph .metric-card-icon,
.metric-card.is-link .metric-card-icon {
  color: #3b82f6;
  background: #eef6ff;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.75fr) minmax(430px, 1.25fr);
  gap: 8px;
  margin-top: 10px;
}

.summary-box,
.recommendation-box {
  min-width: 0;
  padding: 12px;
  border: 1px solid #dce5f2;
  border-radius: 8px;
  background: #fff;
  overflow: visible;
}

.summary-box strong,
.recommendation-box strong {
  display: block;
  margin-bottom: 8px;
  color: #0f2347;
  font-size: 14px;
  font-weight: 800;
}

.summary-box p {
  margin: 0;
  color: #475569;
  font-size: 12px;
  line-height: 1.75;
}

.box-caption {
  display: block;
  margin: -4px 0 9px;
  color: #748298;
  font-size: 12px;
}

.dimension-table {
  margin-top: 0;
}

.root-cause-grid {
  display: grid;
  grid-template-columns: minmax(430px, 1fr) minmax(360px, 0.92fr);
  align-items: stretch;
  gap: 8px;
}

.root-cause-table {
  margin-top: 2px;
}

.root-cause-table :deep(.el-table__expanded-cell) {
  padding: 0;
  background: #f8fafc;
}

.root-cause-evidence-row {
  padding: 12px 16px;
  border-left: 3px solid #7aa7ff;
}

.root-cause-evidence-row span {
  display: block;
  margin-bottom: 5px;
  color: #1f3a63;
  font-size: 12px;
  font-weight: 800;
}

.root-cause-evidence-row p {
  margin: 0;
  color: #334155;
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.root-cause-confidence-cell {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  color: #1f3a63;
  font-size: 12px;
  font-weight: 700;
}

.root-cause-confidence-cell :deep(.el-progress-bar__outer) {
  height: 5px !important;
}

.recommendation-box {
  margin-top: 0;
}

.suggestion-list {
  margin: 0;
  padding-left: 18px;
  color: #243655;
  font-size: 12px;
  line-height: 1.8;
}

.dimension-section {
  padding-top: 12px;
}

.diagnosis-result :deep(.el-alert) {
  border-radius: 6px;
}

.diagnosis-result :deep(.el-alert__title) {
  font-size: 12px;
  line-height: 1.55;
}

.report-success-alert {
  margin-bottom: 6px;
}

.diagnosis-result :deep(.el-table) {
  --el-table-border-color: #e0e8f3;
  --el-table-header-bg-color: #f7faff;
  color: #243655;
  font-size: 12px;
  width: 100%;
}

.diagnosis-result :deep(.el-table th.el-table__cell) {
  background: #f7faff;
  color: #20365c;
  font-weight: 800;
}

.diagnosis-result :deep(.el-table .cell) {
  padding: 0 10px;
  line-height: 1.5;
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.tab-section {
  display: grid;
  gap: 14px;
  padding-top: 8px;
}

.dialog-tab-section {
  max-height: 70vh;
  overflow: auto;
  padding: 4px 2px 10px;
}

.report-detail-launcher {
  padding: 10px 12px;
}

.detail-action-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-action-grid .el-button {
  margin-left: 0;
  min-width: 78px;
  border-radius: 5px;
  color: #1f3a63;
  background: #fff;
  border-color: #dce5f2;
}

.tab-section h3,
.compact-report-body h3 {
  margin: 8px 0 0;
  color: #0f172a;
  font-size: 15px;
}

.compact-report-body {
  display: grid;
  gap: 12px;
}

.compact-report-body p {
  margin: 0;
  color: #475569;
  line-height: 1.7;
}

.report-chart-link {
  border: 1px solid #fecaca;
  background: #fff7f7;
  color: #991b1b;
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
}

.graph-visual-panel,
.diagnosis-attachment-panel {
  margin: 12px 0 16px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}

.graph-visual-header,
.snapshot-titlebar,
.subsection-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid #e5e7eb;
  background: #f8fafc;
}

.graph-visual-header div,
.snapshot-titlebar div,
.subsection-title {
  min-width: 0;
}

.graph-visual-header strong,
.snapshot-titlebar strong,
.subsection-title strong {
  display: block;
  color: #0f172a;
  font-size: 14px;
}

.graph-visual-header span,
.snapshot-titlebar span,
.subsection-title span {
  display: block;
  margin-top: 3px;
  color: #64748b;
  font-size: 12px;
}

.graph-visual-header small {
  flex: 0 0 auto;
  color: #64748b;
}

.graph-visual-canvas {
  width: 100%;
  height: 320px;
  display: block;
  background:
    linear-gradient(90deg, rgba(226, 232, 240, 0.5) 1px, transparent 1px),
    linear-gradient(rgba(226, 232, 240, 0.5) 1px, transparent 1px),
    #ffffff;
  background-size: 38px 38px;
}

.graph-edge-layer line {
  stroke: #64748b;
  stroke-width: 1.4;
}

.graph-edge-layer text {
  fill: #64748b;
  font-size: 10px;
  paint-order: stroke;
  stroke: #fff;
  stroke-width: 4px;
}

.graph-node circle {
  stroke: #fff;
  stroke-width: 3;
  filter: drop-shadow(0 4px 8px rgba(15, 23, 42, 0.18));
}

.graph-node text {
  fill: #fff;
  font-size: 10px;
  font-weight: 700;
  pointer-events: none;
}

.graph-node .node-type {
  font-size: 8px;
  font-weight: 500;
  opacity: 0.86;
}

.diagnosis-attachment-panel {
  display: grid;
  gap: 0;
}

.snapshot-frame {
  background: #fff;
}

.snapshot-frame.clickable {
  cursor: pointer;
}

.snapshot-image-box {
  min-height: 260px;
  display: grid;
  place-items: center;
  padding: 18px;
  background: #fff;
}

.snapshot-image-box img {
  display: block;
  width: 100%;
  max-height: 360px;
  object-fit: contain;
  border: 1px solid #eef2f7;
  background: #fff;
}

.diagnosis-subsection {
  border-top: 1px solid #e5e7eb;
  padding-bottom: 12px;
}

.diagnosis-subsection :deep(.el-table) {
  margin: 12px 14px 0;
  width: calc(100% - 28px);
}

.diagnosis-subsection :deep(.el-table th.el-table__cell) {
  background: #f8fafc;
  color: #0f172a;
}

.report-reader {
  --paper-color: #ffffff;
  --text-color: #000000;
  --font-serif: "Times New Roman", STSong, "SimSun", serif;
  --font-sans: Arial, Helvetica, sans-serif;
  --font-mono: "Courier New", Courier, monospace;
  background-color: #525659;
  margin: 0;
  padding: 40px 20px;
  font-family: var(--font-serif);
  color: var(--text-color);
  line-height: 1.6;
}

.paper {
  background-color: var(--paper-color);
  max-width: 210mm;
  width: 100%;
  min-height: 297mm;
  margin: 0 auto;
  padding: 25.4mm 31.8mm;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
  box-sizing: border-box;
  text-align: justify;
  overflow: visible;
}

.header {
  text-align: center;
  margin-bottom: 40px;
  border-bottom: 1px solid #000;
  padding-bottom: 20px;
}

.doc-type {
  font-family: var(--font-sans);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 2px;
  margin-bottom: 15px;
}

.title {
  font-size: 22px;
  font-weight: bold;
  margin-bottom: 10px;
  line-height: 1.4;
}

.subtitle {
  font-size: 14px;
  font-style: italic;
  margin-bottom: 20px;
}

.authors {
  font-size: 12px;
  font-family: var(--font-sans);
}

.abstract {
  margin: 0 20px 30px 20px;
  font-size: 12px;
  line-height: 1.5;
}

.abstract-title {
  font-weight: bold;
  font-family: var(--font-sans);
  font-size: 12px;
}

.paper h2,
.paper h3,
.paper p {
  margin: 0;
}

.paper h2 {
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: bold;
  margin: 30px 0 10px;
  text-transform: uppercase;
}

.paper h3 {
  margin: 14px 0 8px;
  color: #000;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 700;
}

.paper p {
  font-size: 12px;
  margin-bottom: 12px;
  text-indent: 2em;
}

.math {
  font-family: var(--font-serif);
  font-style: italic;
}

.code-inline,
.paper code {
  font-family: var(--font-mono);
  font-size: 11px;
}

.paper table {
  width: 100%;
  border-collapse: collapse;
  margin: 20px 0 8px;
  font-size: 11px;
  font-family: var(--font-serif);
  border-top: 2px solid #000;
  border-bottom: 2px solid #000;
}

.paper th,
.paper td {
  text-align: center;
  padding: 8px 4px;
  vertical-align: middle;
  overflow-wrap: anywhere;
}

.paper th {
  border-bottom: 1px solid #000;
  font-weight: bold;
}

.paper tbody tr {
  border-bottom: 1px dashed #ccc;
}

.paper tbody tr:last-child {
  border-bottom: 0;
}

.paper td {
  border-bottom: 0;
}

.paper td.group-cell {
  border-bottom: 1px dashed #ccc;
}

.figure-caption {
  text-align: center;
  font-size: 11px;
  font-weight: bold;
  margin-top: 10px;
  margin-bottom: 20px;
}

.report-list {
  font-size: 12px;
  padding-left: 40px;
  margin-bottom: 20px;
}

.report-list li {
  margin-bottom: 6px;
}

.evidence-block {
  border-left: 2px solid #000;
  padding-left: 15px;
  margin: 15px 0;
  font-size: 11px;
  color: #333;
}

.report-spacer {
  height: 8px;
}

.report-chart-link {
  display: block;
  width: 100%;
  margin: 3px 0;
  padding: 7px 9px;
}

.full-preview .report-reader {
  padding: 40px 20px;
}

@media (max-width: 900px) {
  .diagnosis-layout,
  .metric-card-grid,
  .overview-grid,
  .root-cause-grid {
    grid-template-columns: 1fr;
  }

  .diagnosis-result,
  .report-generator-panel,
  .report-history {
    grid-column: auto;
    grid-row: auto;
  }

  .generator-form {
    gap: 12px;
  }

  .config-field,
  .full-row-field {
    flex-basis: 100%;
    width: 100%;
  }

  .report-reader {
    padding: 24px 10px;
  }

  .paper {
    max-width: 100%;
    min-height: auto;
    padding: 18mm 12mm;
  }

}

@media (min-width: 901px) and (max-width: 1360px) {
  .diagnosis-layout {
    grid-template-columns: minmax(360px, 0.8fr) minmax(620px, 1.2fr);
  }

  .metric-card-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .overview-grid,
  .root-cause-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.diagnosis-report-tooltip {
  max-width: min(520px, calc(100vw - 48px));
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
  line-height: 1.6;
}
</style>
