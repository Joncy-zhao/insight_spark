<template>
<div class="diagnosis-layout">
  <div class="panel report-generator-panel">
    <div class="panel-header">
      <div>
        <h2>智能诊断报告</h2>
        <p>对对话查询、看板中的异常数据一键触发 GraphRAG 深度多跳推理，生成可追溯的根因分析报告。</p>
      </div>
      <el-tag type="info" effect="dark">GraphRAG + Neo4j</el-tag>
    </div>

    <el-form label-position="top" class="generator-form">
      <el-form-item label="报告详细程度">
        <el-radio-group v-model="reportGenerateForm.detailLevel">
          <el-radio-button label="simple">简易</el-radio-button>
          <el-radio-button label="detailed">详细</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="异常类型">
        <el-select v-model="reportGenerateForm.anomalyType" class="full-width">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
      </el-form-item>

      <el-form-item label="诊断数据表">
        <el-select v-model="selectedTableName" placeholder="选择数据源" class="full-width" filterable>
          <el-option-group v-if="uploadTables?.length" label="上传数据表">
            <el-option v-for="table in uploadTables" :key="table.tableName" :label="table.displayName" :value="table.tableName" />
          </el-option-group>
          <el-option-group v-if="officialQueryTables?.length" label="官方授权库">
            <el-option v-for="table in officialQueryTables" :key="table.tableName" :label="table.displayName" :value="table.tableName" />
          </el-option-group>
        </el-select>
      </el-form-item>

      <el-form-item label="指标字段">
        <el-select v-model="diagnosisForm.metricField" placeholder="选择数值指标" class="full-width">
          <el-option
            v-for="field in numericFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="维度字段">
        <el-select v-model="diagnosisForm.dimensionFields" multiple placeholder="选择拆解维度" class="full-width">
          <el-option
            v-for="field in dimensionCandidateFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="时间字段">
        <el-select v-model="diagnosisForm.timeField" placeholder="可选" clearable class="full-width">
          <el-option
            v-for="field in dateFields"
            :key="field.columnName"
            :label="field.displayName"
            :value="field.columnName"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="导出内容配置">
        <div class="export-options">
          <el-checkbox v-model="exportOptions.includeSnapshots">包含图表快照</el-checkbox>
          <el-checkbox v-model="exportOptions.includeReasoningLogs">包含推理日志</el-checkbox>
        </div>
      </el-form-item>

      <el-form-item label="PDF 加密设置">
        <el-switch v-model="exportOptions.enablePdfEncryption" />
      </el-form-item>

      <el-form-item label="企业内部文档 / 行业研报">
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
              <el-button class="knowledge-pick-button" :loading="knowledgeDocUploading">
                {{ knowledgeDocUploading ? '正在上传并纳入 GraphRAG...' : '选择文档/研报' }}
              </el-button>
            </el-upload>
          </div>
          <div class="knowledge-file-note">
            <span>{{ selectedKnowledgeDocNames }}</span>
            <small>支持 TXT、Markdown、PDF、Word，用于 GraphRAG 文档扫描和根因证据召回。</small>
          </div>
          <div v-if="knowledgeDocs?.length" class="knowledge-doc-list">
            <div v-for="doc in knowledgeDocs.slice(0, 4)" :key="doc.id" class="knowledge-doc-item">
              <span>{{ doc.title || doc.fileName }}</span>
              <small>{{ doc.docType || 'DOC' }} · {{ doc.chunkCount || 0 }} 个切片</small>
            </div>
          </div>
        </div>
      </el-form-item>

      <div class="generator-actions">
        <el-button type="primary" :loading="diagnosisLoading" :disabled="!diagnosisForm.metricField" @click="runDiagnosisWithDetail">
          生成诊断报告
        </el-button>
        <el-button :loading="diagnosisLoading" :disabled="!currentDiagnosis" @click="runDiagnosisWithDetail">重新生成报告</el-button>
      </div>
    </el-form>

    <div class="generation-progress">
      <div class="progress-header">
        <span>生成进度</span>
        <span>{{ diagnosisProgress.step }}</span>
      </div>
      <el-progress :percentage="diagnosisProgress.percentage" :status="diagnosisLoading ? '' : (currentDiagnosis ? 'success' : undefined)" />
      <div class="progress-steps">
        <span :class="{ active: diagnosisProgress.percentage >= 10 }">任务创建</span>
        <span :class="{ active: diagnosisProgress.percentage >= 35 }">文档扫描</span>
        <span :class="{ active: diagnosisProgress.percentage >= 70 }">多跳推理</span>
        <span :class="{ active: diagnosisProgress.percentage >= 100 }">报告生成</span>
      </div>
    </div>
  </div>

  <div class="panel diagnosis-result">
    <div class="panel-header">
      <div>
        <h2>报告内容</h2>
        <p>包含深度分析文本、数据波动值、异常关联因素、根因定位结论与改进建议，并支持图表快照与异常节点标注。</p>
      </div>
      <div class="diagnosis-actions" v-if="canExportDiagnosis">
        <el-button size="small" @click="exportDiagnosisReportWithOptions('word')">导出 Word</el-button>
        <el-button size="small" type="primary" @click="exportDiagnosisReportWithOptions('pdf')">导出 PDF</el-button>
        <el-button size="small" type="success" @click="previewFullscreenVisible = true">全屏预览</el-button>
      </div>
    </div>

    <el-empty v-if="!currentDiagnosis" description="尚未生成诊断报告" />
    <template v-else>
      <el-alert
        v-if="!isReportPersisted"
        type="warning"
        :closable="false"
        show-icon
        :title="persistWarningTitle"
        style="margin-bottom: 10px;"
      />
      <el-alert type="success" :closable="false" show-icon :title="businessSummaryText(currentDiagnosis)" />

      <section class="report-section">
        <div class="section-heading">
          <div>
            <h3>报告概览</h3>
            <p>核心指标、诊断摘要与关键异常节点。</p>
          </div>
        </div>
        <div class="metric-card-grid">
          <div v-for="card in overviewMetricCards" :key="card.label" class="metric-card">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
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
              <el-table-column prop="time" label="日期/窗口" min-width="130" show-overflow-tooltip />
              <el-table-column prop="valueLabel" label="指标值" width="130" />
              <el-table-column prop="type" label="类型" min-width="160" show-overflow-tooltip />
            </el-table>
          </div>
        </div>
      </section>

      <section class="report-section">
        <div class="section-heading">
          <div>
            <h3>根因定位</h3>
            <p>根因结论、业务维度拆解与改进建议。</p>
          </div>
        </div>
        <div class="root-cause-grid">
          <el-table :data="currentDiagnosis.rootCauses || []" border empty-text="暂无根因假设">
            <el-table-column prop="level" label="等级" width="90" />
            <el-table-column prop="causeType" label="类型" min-width="150" />
            <el-table-column prop="impactField" label="影响对象" min-width="120" />
            <el-table-column prop="confidence" label="置信度" width="90" />
            <el-table-column prop="evidence" label="证据" min-width="260" show-overflow-tooltip />
          </el-table>
          <div class="recommendation-box">
            <strong>改进建议</strong>
            <ul class="suggestion-list">
              <li v-for="suggestion in currentDiagnosis.suggestions || []" :key="suggestion">{{ suggestion }}</li>
            </ul>
          </div>
        </div>
        <el-table class="dimension-table" :data="dimensionBreakdownRows" border empty-text="暂无维度拆解">
          <el-table-column prop="scope" label="口径" width="130" />
          <el-table-column prop="dimension" label="业务维度" min-width="140" />
          <el-table-column prop="factor" label="首要因子" min-width="160" show-overflow-tooltip />
          <el-table-column prop="valueLabel" label="贡献值" width="150" />
          <el-table-column prop="ratioLabel" label="占比" width="110" />
        </el-table>
      </section>

      <section class="report-section report-detail-launcher">
        <div class="section-heading">
          <div>
            <h3>详情查看</h3>
            <p>证据链、图谱推理、图表快照和原始数据按需弹窗查看。</p>
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
              <el-table-column prop="label" label="文档" min-width="160" show-overflow-tooltip />
              <el-table-column prop="sourceType" label="类型" width="110" />
              <el-table-column prop="content" label="命中片段" min-width="300" show-overflow-tooltip />
              <el-table-column prop="score" label="相关度" width="90" />
            </el-table>
            <h3>异常关联因素梳理</h3>
            <el-table :data="currentDiagnosis.relatedKnowledge || []" border empty-text="暂无关联知识">
              <el-table-column prop="nodeType" label="类型" width="140" />
              <el-table-column prop="label" label="名称" min-width="160" />
              <el-table-column prop="sourceType" label="来源" width="100" />
              <el-table-column prop="content" label="说明" min-width="260" show-overflow-tooltip />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'reasoning'" class="tab-section dialog-tab-section">
            <el-alert v-if="currentDiagnosis.graphReasoningPath" type="info" :closable="false" :title="currentDiagnosis.graphReasoningPath" />
            <el-table :data="currentDiagnosis.graphRagEvidenceChain || []" border empty-text="暂无 GraphRAG 证据链">
              <el-table-column prop="step" label="跳数" width="70" />
              <el-table-column prop="hopType" label="推理环节" width="150" />
              <el-table-column prop="label" label="命中对象" min-width="160" show-overflow-tooltip />
              <el-table-column prop="detail" label="证据说明" min-width="320" show-overflow-tooltip />
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
              <el-empty v-else description="暂无可视化图谱节点" :image-size="72" />
            </div>
            <el-table :data="currentDiagnosis.graphEdges || currentDiagnosis.graphPath?.edges || []" border empty-text="暂无图谱边">
              <el-table-column prop="fromKey" label="起点" min-width="220" show-overflow-tooltip />
              <el-table-column prop="relationType" label="关系" width="120" />
              <el-table-column prop="toKey" label="终点" min-width="220" show-overflow-tooltip />
              <el-table-column prop="weight" label="权重" width="90" />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'snapshot'" class="tab-section dialog-tab-section">
            <div class="diagnosis-attachment-panel">
              <div class="snapshot-frame" :class="{ clickable: chartSnapshot?.imageDataUrl }" @click="chartSnapshot?.imageDataUrl && restoreDiagnosisBinding(currentDiagnosis)">
                <div class="snapshot-titlebar">
                  <div>
                    <strong>{{ chartSnapshot?.title || '诊断图表快照' }}</strong>
                    <span>{{ chartSnapshotMeta }}</span>
                  </div>
                  <el-button v-if="chartSnapshot?.imageDataUrl" link type="primary" @click.stop="restoreDiagnosisBinding(currentDiagnosis)">点击回溯</el-button>
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
                  <el-table-column prop="title" label="图表块" min-width="220" show-overflow-tooltip />
                  <el-table-column prop="chartTypeLabel" label="类型" width="110" />
                  <el-table-column prop="dataCount" label="数据点" width="90" />
                  <el-table-column prop="topFactor" label="首要因素" min-width="180" show-overflow-tooltip />
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
                show-overflow-tooltip
              />
            </el-table>
        </div>

        <div v-else-if="detailDialogType === 'fields'" class="tab-section dialog-tab-section">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="指标字段">{{ currentDiagnosis.metricFieldLabel || currentDiagnosis.metricField }}</el-descriptions-item>
              <el-descriptions-item label="时间字段">{{ currentDiagnosis.timeFieldLabel || currentDiagnosis.timeField || '未选择' }}</el-descriptions-item>
              <el-descriptions-item label="维度字段">{{ normalizedDimensionLabels.join('、') || '未选择' }}</el-descriptions-item>
              <el-descriptions-item label="异常类型">{{ currentDiagnosis.anomalyType || diagnosisForm.anomalyType }}</el-descriptions-item>
              <el-descriptions-item label="报告详细程度">{{ currentDiagnosis.detailLevel || diagnosisForm.detailLevel }}</el-descriptions-item>
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

    </template>
  </div>

  <div class="panel report-history">
    <div class="panel-header report-history-header">
      <div class="report-history-copy">
        <h2>报告管理</h2>
        <p>展示个人历史诊断报告，按生成时间排序，支持筛选、预览、重新生成与跳转回原始分析页面。</p>
      </div>
      <div class="history-actions">
        <el-select v-model="reportFilter.type" placeholder="报告类型" clearable class="history-filter">
          <el-option label="简易" value="simple" />
          <el-option label="详细" value="detailed" />
        </el-select>
        <el-select v-model="reportFilter.anomaly" placeholder="异常类型" clearable class="history-filter">
          <el-option label="波动异常" value="fluctuation" />
          <el-option label="结构异常" value="structure" />
          <el-option label="趋势异常" value="trend" />
        </el-select>
        <el-button type="danger" :disabled="!selectedReportIds.length" @click="confirmDeleteReports(selectedReportRows)">
          删除选中
        </el-button>
        <el-button @click="loadDiagnosisReports">刷新</el-button>
      </div>
    </div>

    <el-table
      :data="filteredDiagnosisReports"
      height="360"
      empty-text="暂无历史报告"
      @row-click="loadDiagnosisReportDetail"
      @selection-change="handleReportSelectionChange"
    >
      <el-table-column type="selection" width="46" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="指标" width="130" show-overflow-tooltip>
        <template #default="{ row }">
          <span :title="row.metricField">{{ reportMetricLabel(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="生成时间" min-width="170" />
      <el-table-column label="摘要" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span :title="businessSummaryText(row)">{{ businessSummaryText(row) || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="报告操作" min-width="320">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="bindBackToSource(row)">跳转原始对话/看板</el-button>
          <el-button link type="warning" @click.stop="openRegenerateDialog(row)">重新生成</el-button>
          <el-button link type="danger" @click.stop="confirmDeleteReports([row])">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
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
            <button v-else-if="block.type === 'chart-link'" class="report-chart-link" type="button" @click="restoreDiagnosisBinding(currentDiagnosis)">
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
import { ElMessage, ElMessageBox } from 'element-plus'
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

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
const reportFilter = ref({ type: '', anomaly: '' })
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
const detailDialogTitle = computed(() =>
  reportDetailEntries.find(item => item.type === detailDialogType.value)?.label || '报告详情'
)

const openDetailDialog = (type) => {
  detailDialogType.value = type
  detailDialogVisible.value = true
}

const filteredDiagnosisReports = computed(() => {
  return (diagnosisReports.value || []).filter((report) => {
    const typePass = !reportFilter.value.type || report?.detailLevel === reportFilter.value.type
    const anomalyPass = !reportFilter.value.anomaly || report?.anomalyType === reportFilter.value.anomaly
    return typePass && anomalyPass
  })
})

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

const selectedKnowledgeDocNames = computed(() => {
  const files = knowledgeDocFiles.value || []
  if (!files.length) return '未选择文件'
  if (files.length <= 3) return files.map(file => file.name).join('、')
  return `${files.slice(0, 3).map(file => file.name).join('、')} 等 ${files.length} 个文件`
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
  const rawNodes = Array.isArray(report.graphPath?.nodes) && report.graphPath.nodes.length
    ? report.graphPath.nodes
    : Array.isArray(report.relatedKnowledge) ? report.relatedKnowledge : []
  const rawEdges = Array.isArray(report.graphEdges) && report.graphEdges.length
    ? report.graphEdges
    : Array.isArray(report.graphPath?.edges) ? report.graphPath.edges : []
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
  const fallbackEdges = nodes.slice(1).map((node, index) => ({
    from: nodes[index],
    to: node,
    label: 'RELATED',
    key: `fallback-${index}`
  }))
  const edges = rawEdges.map((edge, index) => {
    const from = nodeMap.get(edge.fromKey) || nodeMap.get(edge.from) || nodes[index % Math.max(nodes.length, 1)]
    const to = nodeMap.get(edge.toKey) || nodeMap.get(edge.to) || nodes[(index + 1) % Math.max(nodes.length, 1)]
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
  return { nodes, edges: edges.length ? edges.slice(0, 18) : fallbackEdges.slice(0, 8) }
})

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
    { label: '有效记录', value: formatInteger(stats.count) },
    { label: '指标合计', value: formatReportValue(stats.total) },
    { label: '平均值', value: formatReportValue(stats.avg) },
    { label: '异常节点', value: formatInteger(anomalyNodeRows.value.length) },
    { label: '图谱节点', value: formatInteger(graphVisual.value.nodes.length) },
    { label: '图谱关系', value: formatInteger(graphVisual.value.edges.length) }
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
  const graphEdges = Array.isArray(report.graphEdges) ? report.graphEdges : (Array.isArray(report.graphPath?.edges) ? report.graphPath.edges : [])
  const dimensions = normalizedDimensionLabels.value
  const timeLabel = report.timeFieldLabel || report.timeField || '日期'
  const rootCauseName = rootCauses[0]?.causeType || '证据不足，暂未形成单一收敛根因'
  const rootCauseConclusion = rootCauses.length
    ? `最终将核心根因指向「${escapeHtml(rootCauseName)}」。`
    : `当前未形成单一收敛根因，系统将结论标定为「${escapeHtml(rootCauseName)}」。`
  const dimensionChain = dimensions.join('/') || '未选择维度字段'
  const confidenceBands = confidenceBandText(rootCauses)
  const article = {
    docType: 'Diagnostic Analysis Report | Insight Spark System',
    title: '基于 GraphRAG 的多跳关联推理与业务指标异常归因分析',
    subtitleHtml: `—— 以数据集 <span class="code-inline">${escapeHtml(tableName)}</span> ${escapeHtml(metricLabel)}指标为例`,
    authorsHtml: `自动生成环境: 智能诊断引擎 (Build: 2026.05) <br>诊断时间: ${escapeHtml(createdAt)}`,
    blocks
  }

  blocks.push({
    type: 'abstract',
    html: `<span class="abstract-title">Abstract / 诊断摘要：</span>
        本次分析围绕核心业务指标「${escapeHtml(metricLabel)}」展开。系统在有效观测区间内提取了 ${formatInteger(stats.count || rawDataRows.value.length)} 条样本记录进行异常扫描。统计结果显示，样本总计数值为 ${formatReportValue(stats.total)}，均值 (<span class="math">μ</span>) 为 ${formatReportValue(stats.avg)}，区间极值分别为 <span class="math">Max</span> = ${formatReportValue(stats.max)} 与 <span class="math">Min</span> = ${formatReportValue(stats.min)}。通过统计算法，系统识别出 ${markers.length} 个具备统计学显著性的异常节点。为探究异常机制，系统引入 GraphRAG（Graph Retrieval-Augmented Generation）技术，融合业务维度拆解、时序窗口回溯与 Neo4j 知识图谱（涉及 ${graphNodes.length} 个节点与 ${graphEdges.length} 条边），${rootCauseConclusion}本文档详细记录了数据特征、多跳推理路径及多维度异质性分析结果。`
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

  blocks.push({ type: 'h2', text: 'V. 结论与对策建议 (Conclusion & Recommendations)' })
  blocks.push({
    type: 'paragraph',
    text: '综上分析，本次指标异动具有显著的结构性与节点性特征。为防范潜在的业务连续性风险并优化数据观测模型，提出以下干预建议：'
  })
  blocks.push({
    type: 'list',
    htmlItems: suggestions.length ? suggestions.map(item => escapeHtml(item)) : fallbackReportSuggestions(metricLabel, dimensions, timeLabel, report).map(item => escapeHtml(item))
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
  if (exportOptions.value.includeSnapshots && chartSnapshot.value?.imageDataUrl) {
    article.blocks.push({ type: 'h2', text: 'VI. 图表快照 (Chart Snapshot)' })
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
      article.blocks.push({ type: 'h2', text: 'VII. GraphRAG 推理日志 (Reasoning Logs)' })
      article.blocks.push({
        type: 'table',
        headers: ['步骤 (Step)', '环节 (Stage)', '过程说明 (Detail)'].map(renderInlineMarkdown),
        rows: buildReportTableRows([], logs.map(log => [
          escapeHtml(`Step ${log.step ?? '-'}`),
          escapeHtml(log.title || '-'),
          escapeHtml(log.detail || '-')
        ]))
      })
      article.blocks.push({ type: 'caption', text: '表 III. GraphRAG 多跳推理过程日志' })
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
  const preview = evidence.slice(0, 2).map(item => `《${escapeHtml(item.label || item.source || '知识文档')}》${escapeHtml(item.content || item.text || '')}`).join('；')
  return `<b>检索证据摘要 (Corpus Evidence Note)：</b><br>在 RAG 检索阶段，命中 ${evidence.length} 条企业内部复盘文档或外部行业研报。代表性证据包括：${preview}。`
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
  const evidence = escapeHtml(cause.evidence || '')
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

const canExportDiagnosis = computed(() => Boolean(currentDiagnosis.value?.id && isReportPersisted.value))

const runDiagnosisWithDetail = async () => {
  diagnosisForm.value.detailLevel = reportGenerateForm.value.detailLevel
  diagnosisForm.value.anomalyType = reportGenerateForm.value.anomalyType
  await runDiagnosis()
}

const exportDiagnosisReportWithOptions = async (format) => {
  if (!currentDiagnosis.value) {
    ElMessage.warning('请先生成或选择一份诊断报告')
    return
  }
  await nextTick()
  const payload = {
    includeSnapshots: exportOptions.value.includeSnapshots,
    includeReasoningLogs: exportOptions.value.includeReasoningLogs,
    enablePdfEncryption: exportOptions.value.enablePdfEncryption
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
    format: 'a4',
    ...(options.enablePdfEncryption ? {
      encryption: {
        userPassword: 'insight-spark',
        ownerPassword: 'insight-spark',
        userPermissions: ['print']
      }
    } : {})
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
  pdf.save(`${reportExportBaseName()}.pdf`)
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
  const title = currentDiagnosis.value?.title || reportArticle.value.title || '智能诊断报告'
  return String(title).replace(/[\\/:*?"<>|]/g, '_')
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
  margin-top: 14px;
}

.generator-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.generation-progress {
  margin-top: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 12px;
  background: #fafafa;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  color: #475569;
  margin-bottom: 8px;
}

.progress-steps {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}

.progress-steps .active {
  color: #2563eb;
  font-weight: 700;
}

.history-actions {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(140px, 1fr)) auto auto;
  align-items: center;
  gap: 8px;
  justify-content: stretch;
}

.report-history-header {
  align-items: flex-start;
  flex-wrap: wrap;
}

.report-history-copy {
  flex: 1 1 280px;
  min-width: 260px;
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

@media (max-width: 760px) {
  .history-actions {
    grid-template-columns: 1fr 1fr;
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
  gap: 16px;
  flex-wrap: wrap;
}

.field-label-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.knowledge-upload-panel {
  width: 100%;
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

.knowledge-file-note {
  display: grid;
  gap: 4px;
  margin-top: 8px;
  color: #475569;
  font-size: 13px;
}

.knowledge-file-note small {
  color: #64748b;
  line-height: 1.5;
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
  margin-top: 16px;
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.section-heading h3 {
  margin: 0;
  color: #0f172a;
  font-size: 16px;
}

.section-heading p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}

.metric-card-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  min-width: 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.metric-card span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.metric-card strong {
  display: block;
  margin-top: 6px;
  color: #0f172a;
  font-size: 18px;
  overflow-wrap: anywhere;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(360px, 1.1fr);
  gap: 12px;
  margin-top: 12px;
}

.summary-box,
.recommendation-box {
  min-width: 0;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.summary-box strong,
.recommendation-box strong {
  display: block;
  margin-bottom: 8px;
  color: #0f172a;
}

.summary-box p {
  margin: 0;
  color: #475569;
  line-height: 1.7;
}

.dimension-table {
  margin-top: 12px;
}

.root-cause-grid {
  display: grid;
  gap: 12px;
}

.recommendation-box {
  margin-top: 0;
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
  padding-bottom: 14px;
}

.detail-action-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.detail-action-grid .el-button {
  margin-left: 0;
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
  min-height: 297mm;
  margin: 0 auto;
  padding: 25.4mm 31.8mm;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
  box-sizing: border-box;
  text-align: justify;
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
  .metric-card-grid,
  .overview-grid,
  .root-cause-grid {
    grid-template-columns: 1fr;
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
</style>
