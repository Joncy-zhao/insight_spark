<template>
  <section class="chart-rule-page">
    <header class="chart-rule-hero">
      <div>
        <h1>AI图表推荐规则配置</h1>
        <p>自定义 AI 自动推荐图表规则引擎，统一企业图表偏好与 ECharts 动态渲染参数，支撑趋势、对比、占比、明细等可视化场景。</p>
      </div>
    </header>

    <div class="metric-grid">
      <div class="metric-card metric-blue">
        <div class="metric-icon">
          <el-icon><DataAnalysis /></el-icon>
        </div>
        <div class="metric-copy">
          <span>总推荐规则</span>
          <strong>{{ totalRuleCount }}</strong>
          <small>覆盖 {{ scenarioCount }} 类业务场景</small>
        </div>
      </div>

      <div class="metric-card metric-green">
        <div class="metric-icon">
          <el-icon><CircleCheck /></el-icon>
        </div>
        <div class="metric-copy">
          <span>已启用规则</span>
          <strong>{{ enabledRuleCount }}</strong>
          <small>启用率 {{ enabledRuleRate }}%</small>
        </div>
      </div>

      <div class="metric-card metric-purple">
        <div class="metric-icon">
          <el-icon><TrendCharts /></el-icon>
        </div>
        <div class="metric-copy">
          <span>时序场景规则</span>
          <strong>{{ timeSeriesRuleCount }}</strong>
          <small>支持趋势与预测</small>
        </div>
      </div>

      <div class="metric-card metric-orange">
        <div class="metric-icon">
          <el-icon><Operation /></el-icon>
        </div>
        <div class="metric-copy">
          <span>高优先级规则</span>
          <strong>{{ highPriorityRuleCount }}</strong>
          <small>优先级大于等于 800</small>
        </div>
      </div>
    </div>

    <div class="rule-workspace">
      <main class="workspace-main">
        <el-card shadow="never" class="panel rule-panel">
          <template #header>
            <div class="panel-heading">
              <div>
                <h3>规则配置管理</h3>
                <p>{{ totalRuleCount }} 条规则，{{ enabledRuleCount }} 条启用</p>
              </div>
              <el-segmented v-model="filters.scenarioType" :options="scenarioOptions" class="rule-segment" @change="loadRules" />
            </div>
          </template>

          <div class="rule-toolbar">
            <el-input
              v-model="filters.keyword"
              :prefix-icon="Search"
              clearable
              placeholder="搜索规则名称、编码、说明"
              class="rule-search"
              @keyup.enter="loadRules"
            />
            <el-select v-model="filters.enabled" clearable placeholder="启用状态" class="status-select" @change="loadRules">
              <el-option label="已启用" :value="true" />
              <el-option label="已禁用" :value="false" />
            </el-select>
            <el-button :icon="Search" @click="loadRules">查询</el-button>
            <el-button :icon="Download" @click="exportConfig">导出配置</el-button>
            <el-button :icon="Upload" @click="importDialogVisible = true">导入配置</el-button>
            <el-button :icon="Refresh" @click="loadAll" :loading="loading">刷新</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新增规则</el-button>
          </div>

          <el-table :data="pagedRules" border v-loading="loading" height="438" empty-text="暂无规则" row-key="id">
            <el-table-column prop="priority" label="优先级" width="82" />
            <el-table-column label="规则" min-width="190">
              <template #default="{ row }">
                <div class="rule-name">{{ row.ruleName }}</div>
                <div class="muted mono">{{ row.ruleCode }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="scenarioType" label="场景" width="132">
              <template #default="{ row }">
                <el-tag size="small" effect="light" :class="scenarioTagClass(row.scenarioType)">
                  {{ scenarioLabel(row.scenarioType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="图表" width="104">
              <template #default="{ row }">
                <span class="chart-type-text">{{ chartTypeLabel(row.chartType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="86">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" @change="toggleRule(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="168" />
            <el-table-column label="操作" width="196" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="primary" @click="openVersions(row)">版本</el-button>
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="table-footer">
            <el-pagination
              v-model:current-page="rulePage"
              v-model:page-size="rulePageSize"
              :page-sizes="[10, 20, 50]"
              :total="totalRuleCount"
              background
              small
              layout="total, sizes, prev, pager, next"
              @size-change="rulePage = 1"
            />
          </div>
        </el-card>

        <el-card shadow="never" class="panel audit-panel">
          <template #header>
            <div class="panel-heading compact-heading">
              <div>
                <h3>规则审计日志</h3>
                <p>共 {{ filteredAuditLogs.length }} / {{ auditLogs.length }} 条变更记录</p>
              </div>
              <div class="audit-head-actions">
                <el-button type="primary" :icon="Refresh" @click="loadAuditLogs">刷新</el-button>
                <el-button :icon="Search" class="audit-query-button">查询</el-button>
                <el-button class="audit-reset-button" @click="resetAuditFilters">重置</el-button>
              </div>
            </div>
          </template>
          <div class="audit-filter-bar">
            <el-select v-model="auditFilters.action" clearable filterable placeholder="动作">
              <el-option v-for="item in auditActionOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input v-model="auditFilters.ruleId" clearable placeholder="规则ID" />
            <el-date-picker
              v-model="auditFilters.timeRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              class="audit-time-range"
            />
          </div>
          <el-table :data="filteredAuditLogs" size="small" height="100%" empty-text="暂无审计记录" class="audit-log-table">
            <el-table-column label="动作" width="132">
              <template #default="{ row }">
                <el-tag size="small" effect="light" :class="auditActionTagClass(row.action)">
                  {{ auditActionLabel(row.action) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ruleId" label="规则ID" min-width="82" />
            <el-table-column prop="operator" label="操作人" min-width="96" />
            <el-table-column prop="createdAt" label="时间" min-width="152" />
            <el-table-column label="变更快照" min-width="260">
              <template #default="{ row }">
                <el-tooltip
                  :content="auditChangeDescription(row)"
                  placement="top-start"
                  effect="light"
                  popper-class="audit-change-tooltip"
                >
                  <div class="audit-change-text">
                    {{ auditChangeDescription(row) }}
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </main>

      <aside class="workspace-side">
        <el-card shadow="never" class="panel side-panel preference-panel">
          <template #header>
            <div class="side-title">
              <el-icon><Setting /></el-icon>
              <span>企业图表偏好</span>
            </div>
          </template>
          <el-form label-position="top">
            <el-form-item label="主题名称">
              <el-input v-model="preferences.themeName" />
            </el-form-item>
            <el-form-item label="颜色盘">
              <div class="palette-row">
                <div v-for="(_, index) in preferences.colorPalette" :key="index" class="palette-item">
                  <el-color-picker v-model="preferences.colorPalette[index]" show-alpha />
                  <el-tooltip content="删除颜色" placement="top">
                    <el-button
                      :icon="Close"
                      class="palette-remove"
                      circle
                      size="small"
                      @click="removePaletteColor(index)"
                    />
                  </el-tooltip>
                </div>
                <el-tooltip content="新增颜色" placement="top">
                  <el-button :icon="Plus" circle @click="addPaletteColor" />
                </el-tooltip>
              </div>
            </el-form-item>
            <el-form-item label="字体">
              <div class="inline-grid">
                <el-select
                  v-model="preferences.fontConfig.fontFamily"
                  filterable
                  allow-create
                  default-first-option
                  placeholder="选择字体"
                >
                  <el-option v-for="item in fontFamilyOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
                <el-input-number v-model="preferences.fontConfig.fontSize" :min="10" :max="28" />
              </div>
            </el-form-item>
            <el-form-item label="布局">
              <div class="inline-grid">
                <el-select v-model="preferences.layoutConfig.legend">
                  <el-option label="顶部" value="top" />
                  <el-option label="底部" value="bottom" />
                  <el-option label="左侧" value="left" />
                  <el-option label="右侧" value="right" />
                </el-select>
                <el-input-number v-model="preferences.layoutConfig.height" :min="240" :max="800" />
              </div>
            </el-form-item>
            <el-form-item label="动态渲染">
              <div class="switch-line">
                <el-checkbox v-model="preferences.defaultOptions.animation">动画</el-checkbox>
                <el-checkbox v-model="preferences.defaultOptions.dataZoom">缩放</el-checkbox>
                <el-checkbox v-model="preferences.defaultOptions.voiceSummary">语音播报适配</el-checkbox>
              </div>
            </el-form-item>
            <el-button type="primary" class="full-width" :loading="savingPreference" @click="savePreference">保存偏好</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel side-panel test-panel">
          <template #header>
            <div class="side-title">
              <el-icon><Monitor /></el-icon>
              <span>图表推荐测试与预览</span>
            </div>
          </template>
          <el-form label-position="top">
            <el-form-item label="业务意图">
              <el-input v-model="tester.intent" placeholder="例如：查看各部门销售额对比" />
            </el-form-item>
            <div class="sample-quick-row">
              <span>快捷样例</span>
              <el-button v-for="item in samplePresets" :key="item.key" size="small" @click="applySamplePreset(item.key)">
                {{ item.label }}
              </el-button>
            </div>
            <div class="sample-summary-card">
              <div>
                <span>当前样例数据</span>
                <strong>{{ activeSampleFields.length }} 个字段 / {{ sampleEditor.rows.length }} 行</strong>
              </div>
              <el-button type="primary" plain @click="sampleEditorDialogVisible = true">编辑样例数据</el-button>
            </div>
            <el-button type="primary" class="full-width" :loading="testing" @click="runTest">测试推荐</el-button>
          </el-form>
          <div v-if="testResult" class="test-summary">
            <el-tag type="success">{{ testResult.chartType }}</el-tag>
            <el-tooltip
                v-if="previewAnimationMeta"
                :content="`${previewAnimationMeta.label}，${previewAnimationMeta.mode}，入场 ${previewAnimationMeta.duration}ms，更新 ${previewAnimationMeta.updateDuration}ms`"
                placement="top"
            >
              <el-tag :type="previewAnimationMeta.enabled ? 'success' : 'info'" effect="plain">
                动画{{ previewAnimationMeta.enabled ? '开启' : '关闭' }} · {{ previewAnimationMeta.duration }}ms
              </el-tag>
            </el-tooltip>
            <span>{{ testResult.explain }}</span>
          </div>
          <div ref="chartRef" class="chart-preview"></div>
        </el-card>
      </aside>
    </div>

    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑规则' : '新增规则'" size="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="规则名称">
          <el-input v-model="form.ruleName" />
        </el-form-item>
        <el-form-item label="规则编码">
          <el-input v-model="form.ruleCode" />
        </el-form-item>
        <div class="inline-grid">
          <el-form-item label="场景">
            <el-select v-model="form.scenarioType">
              <el-option v-for="item in scenarioSelectOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="图表类型">
            <el-select v-model="form.chartType">
              <el-option label="折线图 line" value="line" />
              <el-option label="柱状图 bar" value="bar" />
              <el-option label="饼图 pie" value="pie" />
              <el-option label="环形图 doughnut" value="doughnut" />
              <el-option label="表格 table" value="table" />
            </el-select>
          </el-form-item>
        </div>
        <div class="inline-grid">
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="0" :max="9999" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
        <el-divider content-position="left">结构化匹配条件</el-divider>
        <div class="structured-grid">
          <el-form-item label="字段类型条件" class="structured-span">
            <div class="switch-line">
              <el-checkbox v-model="structuredMatch.timeRequired" @change="syncStructuredMatchToJson">需要时间字段</el-checkbox>
              <el-checkbox v-model="structuredMatch.numericRequired" @change="syncStructuredMatchToJson">需要数值字段</el-checkbox>
              <el-checkbox v-model="structuredMatch.dimensionRequired" @change="syncStructuredMatchToJson">需要维度字段</el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="字段名关键词">
            <el-input v-model="structuredMatch.fieldKeywordsText" placeholder="逗号分隔，如 date,sales,profit" @blur="syncStructuredMatchToJson" />
          </el-form-item>
          <el-form-item label="指标数量">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.metricMin" :min="0" :max="20" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.metricMax" :min="0" :max="50" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="维度数量">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.dimensionMin" :min="0" :max="20" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.dimensionMax" :min="0" :max="50" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="行数范围">
            <div class="range-row">
              <el-input-number v-model="structuredMatch.rowMin" :min="0" :max="1000000" controls-position="right" @change="syncStructuredMatchToJson" />
              <span>至</span>
              <el-input-number v-model="structuredMatch.rowMax" :min="0" :max="1000000" controls-position="right" @change="syncStructuredMatchToJson" />
            </div>
          </el-form-item>
          <el-form-item label="业务意图关键词">
            <el-input v-model="structuredMatch.intentKeywordsText" placeholder="逗号分隔，如 趋势,对比,占比" @blur="syncStructuredMatchToJson" />
          </el-form-item>
        </div>
        <el-form-item label="行业场景标签">
          <el-select v-model="structuredMatch.industryTags" multiple filterable allow-create default-first-option @change="syncStructuredMatchToJson">
            <el-option label="电商" value="ecommerce" />
            <el-option label="金融" value="finance" />
            <el-option label="零售" value="retail" />
            <el-option label="制造" value="manufacturing" />
            <el-option label="通用经营分析" value="business" />
          </el-select>
        </el-form-item>
        <el-form-item label="匹配条件 JSON">
          <el-input v-model="form.matchConfigText" type="textarea" :rows="6" @blur="syncJsonToStructuredMatch" />
        </el-form-item>
        <el-divider content-position="left">动态渲染配置</el-divider>
        <div class="dynamic-render-panel">
          <div class="switch-line prediction-switches">
            <el-checkbox v-model="structuredRender.animation" @change="syncStructuredRenderToJson">动画</el-checkbox>
            <el-checkbox v-model="structuredRender.tooltip" @change="syncStructuredRenderToJson">Tooltip</el-checkbox>
            <el-checkbox v-model="structuredRender.dataZoom" @change="syncStructuredRenderToJson">DataZoom</el-checkbox>
            <el-checkbox v-model="structuredRender.incrementalRendering" @change="syncStructuredRenderToJson">增量渲染</el-checkbox>
          </div>
          <div class="structured-grid">
            <el-form-item label="刷新间隔(秒)">
              <el-input-number v-model="structuredRender.refreshIntervalSeconds" :min="0" :max="3600" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="缩放阈值">
              <el-input-number v-model="structuredRender.autoDataZoomThreshold" :min="4" :max="500" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="图例滚动阈值">
              <el-input-number v-model="structuredRender.autoLegendScrollThreshold" :min="4" :max="500" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="渐进批量">
              <el-input-number v-model="structuredRender.progressive" :min="0" :max="20000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="渐进阈值">
              <el-input-number v-model="structuredRender.progressiveThreshold" :min="0" :max="100000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
            <el-form-item label="大数据阈值">
              <el-input-number v-model="structuredRender.largeThreshold" :min="0" :max="100000" controls-position="right" @change="syncStructuredRenderToJson" />
            </el-form-item>
          </div>
        </div>
        <el-divider content-position="left">语音播报配置</el-divider>
        <div class="voice-config-panel">
          <el-checkbox v-model="structuredVoice.enabled" @change="syncStructuredVoiceToJson">启用语音摘要</el-checkbox>
          <el-form-item label="播报顺序">
            <el-select v-model="structuredVoice.order" multiple class="full-width" @change="syncStructuredVoiceToJson">
              <el-option v-for="item in voiceFieldOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="当前图表模板">
            <el-input
              v-model="structuredVoice.currentTemplate"
              type="textarea"
              :rows="3"
              placeholder="可用变量：{ruleName}、{chartTypeName}、{dimension}、{metric}、{count}、{maxName}、{maxValue}、{minName}、{minValue}、{trend}、{anomalyCount}"
              @blur="syncStructuredVoiceToJson"
            />
          </el-form-item>
        </div>
        <template v-if="form.chartType === 'line'">
          <el-divider content-position="left">预测渲染配置</el-divider>
          <div class="prediction-panel">
            <div class="switch-line prediction-switches">
              <el-checkbox v-model="structuredPrediction.enabled" @change="syncStructuredPredictionToJson">启用预测曲线</el-checkbox>
              <el-checkbox v-model="structuredPrediction.showExplanation" @change="syncStructuredPredictionToJson">显示预测说明</el-checkbox>
            </div>
            <div class="structured-grid">
              <el-form-item label="置信度">
                <el-input-number v-model="structuredPrediction.confidence" :min="0.5" :max="0.99" :step="0.01" :precision="2" controls-position="right" @change="syncStructuredPredictionToJson" />
              </el-form-item>
              <el-form-item label="预测期数">
                <el-input-number v-model="structuredPrediction.horizon" :min="1" :max="60" controls-position="right" @change="syncStructuredPredictionToJson" />
              </el-form-item>
              <el-form-item label="预测算法" class="structured-span">
                <el-select v-model="structuredPrediction.algorithm" filterable allow-create default-first-option @change="syncStructuredPredictionToJson">
                  <el-option label="Holt-Winters" value="Holt-Winters" />
                  <el-option label="Prophet" value="Prophet" />
                  <el-option label="移动平均" value="Moving-Average" />
                </el-select>
              </el-form-item>
            </div>
            <div class="prediction-legend-grid">
              <div v-for="item in predictionLegendItems" :key="item.key" class="prediction-legend-row">
                <el-checkbox v-model="structuredPrediction[item.showKey]" @change="syncStructuredPredictionToJson">{{ item.name }}</el-checkbox>
                <el-input v-model="structuredPrediction[item.labelKey]" size="small" @blur="syncStructuredPredictionToJson" />
              </div>
            </div>
          </div>
        </template>
        <el-form-item label="ECharts 渲染配置 JSON">
          <el-input v-model="form.renderConfigText" type="textarea" :rows="6" @blur="syncJsonToStructuredRenderConfig" />
        </el-form-item>
        <el-form-item label="推荐解释">
          <el-input v-model="form.explainTemplate" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRule" @click="saveRule">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="sampleEditorDialogVisible" title="编辑测试样例数据" width="860px" destroy-on-close>
      <div class="sample-dialog-layout">
        <div class="sample-dialog-head">
          <div>
            <h4>字段配置</h4>
            <p>配置测试数据中的字段名称和类型，前端会自动生成接口需要的 JSON。</p>
          </div>
          <el-button :icon="Plus" @click="addSampleField">新增字段</el-button>
        </div>
        <div class="sample-field-list sample-field-list--dialog">
          <div v-for="(field, index) in sampleEditor.fields" :key="field.id" class="sample-field-row sample-field-row--dialog">
            <el-input v-model="field.name" placeholder="字段名" @input="syncSampleJsonFromEditor" />
            <el-select v-model="field.type" placeholder="字段类型" @change="syncSampleJsonFromEditor">
              <el-option label="文本" value="string" />
              <el-option label="数值" value="number" />
              <el-option label="日期" value="date" />
            </el-select>
            <el-button :icon="Close" circle @click="removeSampleField(index)" />
          </div>
        </div>

        <div class="sample-dialog-head">
          <div>
            <h4>样例行数据</h4>
            <p>可横向滚动编辑多字段数据，适合批量录入多行企业样例。</p>
          </div>
          <el-button :icon="Plus" @click="addSampleRow">新增行</el-button>
        </div>
        <div class="sample-row-table sample-row-table--dialog">
          <el-table :data="sampleEditor.rows" size="small" height="320" empty-text="暂无样例行">
            <el-table-column
              v-for="field in activeSampleFields"
              :key="field.name"
              :label="field.name"
              min-width="150"
            >
              <template #default="{ row }">
                <el-input v-model="row[field.name]" size="small" @input="syncSampleJsonFromEditor" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="82" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeSampleRow($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <details class="sample-json-advanced">
          <summary>高级：查看/编辑 JSON</summary>
          <el-input
            v-model="tester.json"
            type="textarea"
            :rows="8"
            @blur="syncSampleEditorFromJson"
          />
        </details>
      </div>
      <template #footer>
        <el-button @click="sampleEditorDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="sampleEditorDialogVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialogVisible" title="导入规则配置" width="780px" destroy-on-close @closed="resetImportState">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="请先上传 JSON 配置文件或在高级入口粘贴 JSON，必须预览差异后才能确认导入。导入不会删除当前系统中未出现在文件里的规则。"
        class="import-alert"
      />
      <div class="import-upload-panel">
        <div class="import-upload-copy">
          <h4>上传 JSON 配置文件</h4>
          <p>支持导入从本系统导出的 .json 配置文件，上传后请先预览差异。</p>
        </div>
        <el-upload
          drag
          accept=".json,application/json"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="handleImportFile"
          class="import-upload"
        >
          <el-icon class="import-upload-icon"><Upload /></el-icon>
          <div class="import-upload-text">
            <strong>{{ importFileName || '点击或拖拽 JSON 文件到此处' }}</strong>
            <span>文件内容会自动填入导入配置，不会立即执行导入。</span>
          </div>
        </el-upload>
      </div>

      <details class="import-advanced">
        <summary>高级入口：粘贴 JSON 配置</summary>
        <el-input
          v-model="importText"
          type="textarea"
          :rows="8"
          placeholder="请粘贴导出的 JSON 配置"
          @input="invalidateImportPreview"
        />
      </details>

      <div v-if="importPreview" class="import-preview">
        <div class="import-preview-stats">
          <div class="import-preview-stat stat-create">
            <span>新增</span>
            <strong>{{ importPreview.createCount || 0 }}</strong>
          </div>
          <div class="import-preview-stat stat-update">
            <span>更新</span>
            <strong>{{ importPreview.overwriteCount || 0 }}</strong>
          </div>
          <div class="import-preview-stat stat-skip">
            <span>跳过</span>
            <strong>{{ importPreview.unchangedCount || 0 }}</strong>
          </div>
          <div class="import-preview-stat stat-conflict">
            <span>冲突</span>
            <strong>{{ importPreview.conflictCount || 0 }}</strong>
          </div>
        </div>
        <el-tag v-if="importPreview.preferenceIncluded" type="primary">包含企业偏好</el-tag>
        <el-table :data="importPreview.changes || []" size="small" height="180" class="import-preview-table">
          <el-table-column prop="ruleCode" label="规则编码" min-width="160" />
          <el-table-column prop="ruleName" label="规则名称" min-width="180" />
          <el-table-column label="动作" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="light" :type="importActionTagType(row.action)">
                {{ importActionLabel(row.action) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="closeImportDialog">取消</el-button>
        <el-button :disabled="!canPreviewImport" :loading="importing" @click="previewImport">预览差异</el-button>
        <el-button type="primary" :disabled="!importPreview" :loading="importing" @click="applyImport">确认导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionDialogVisible" :title="versionDialogTitle" width="860px" destroy-on-close>
      <el-table :data="ruleVersions" v-loading="versionLoading" size="small" height="360" empty-text="暂无版本记录">
        <el-table-column prop="versionNo" label="版本" width="78" />
        <el-table-column prop="changeAction" label="动作" width="130" />
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column prop="createdAt" label="时间" width="168" />
        <el-table-column label="快照摘要" min-width="340">
          <template #default="{ row }">
            <div class="version-summary">
              <div class="version-summary-main">{{ row.snapshot?.ruleName || '-' }}</div>
              <div class="version-summary-meta">
                <span class="version-summary-code muted mono">{{ row.snapshot?.ruleCode || '' }}</span>
                <el-tag size="small" effect="light" :class="scenarioTagClass(row.snapshot?.scenarioType)">
                  {{ scenarioLabel(row.snapshot?.scenarioType) }}
                </el-tag>
                <el-tag size="small" type="info">{{ chartTypeLabel(row.snapshot?.chartType) }}</el-tag>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.versionNo === latestVersionNo" @click="rollbackVersion(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="versionDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheck,
  Close,
  DataAnalysis,
  Download,
  Monitor,
  Operation,
  Plus,
  Refresh,
  Search,
  Setting,
  TrendCharts,
  Upload
} from '@element-plus/icons-vue'
import {
  applyDynamicInteractionDefaults,
  applyOptionTemplateDefaults,
  buildAnimationReplayStartOption,
  buildForecastChartOption,
  getChartAnimationMeta,
  hasForecastSeriesRows
} from '../../utils/chartOptionFromSnapshot'
import {
  createChartRule,
  deleteChartRule,
  exportChartRuleConfig,
  fetchChartPreferences,
  fetchChartRuleAuditLogs,
  fetchChartRuleVersions,
  fetchChartRules,
  importChartRuleConfig,
  previewImportChartRuleConfig,
  rollbackChartRuleVersion,
  saveChartPreferences,
  testChartRule,
  updateChartRule,
  updateChartRuleEnabled
} from '../../api/aiChartRuleConfig'

const scenarioSelectOptions = [
  { label: '全部', value: '' },
  { label: '时序趋势', value: 'TIME_SERIES' },
  { label: '分组对比', value: 'GROUP_COMPARE' },
  { label: '占比分析', value: 'RATIO' },
  { label: '明细数据', value: 'DETAIL' },
  { label: '自定义', value: 'CUSTOM' }
]
const scenarioOptions = scenarioSelectOptions.map((item) => ({ label: item.label, value: item.value }))
const chartTypeLabels = {
  line: '折线图',
  bar: '柱状图',
  pie: '饼图',
  doughnut: '环形图',
  table: '表格'
}
const scenarioTagClassMap = {
  TIME_SERIES: 'scenario-tag--time-series',
  GROUP_COMPARE: 'scenario-tag--group-compare',
  RATIO: 'scenario-tag--ratio',
  DETAIL: 'scenario-tag--detail',
  CUSTOM: 'scenario-tag--custom'
}
const auditActionLabels = {
  INIT: '初始化',
  CREATE: '新增规则',
  UPDATE: '更新规则',
  DELETE: '删除规则',
  ENABLE: '启用规则',
  DISABLE: '禁用规则',
  ROLLBACK: '版本回滚',
  TEST: '测试推荐',
  IMPORT: '导入配置',
  EXPORT: '导出配置',
  PREFERENCE_UPDATE: '偏好更新'
}
const auditActionTagClassMap = {
  INIT: 'audit-action-tag--init',
  CREATE: 'audit-action-tag--create',
  UPDATE: 'audit-action-tag--update',
  DELETE: 'audit-action-tag--delete',
  ENABLE: 'audit-action-tag--enable',
  DISABLE: 'audit-action-tag--disable',
  ROLLBACK: 'audit-action-tag--rollback',
  TEST: 'audit-action-tag--test',
  IMPORT: 'audit-action-tag--import',
  EXPORT: 'audit-action-tag--export',
  PREFERENCE_UPDATE: 'audit-action-tag--preference'
}
const paletteSuggestions = ['#2563eb', '#16a34a', '#f59e0b', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#475569']
const fontFamilyOptions = [
  { label: 'Microsoft YaHei（微软雅黑）', value: 'Microsoft YaHei' },
  { label: 'PingFang SC（苹方）', value: 'PingFang SC' },
  { label: 'SimHei（黑体）', value: 'SimHei' },
  { label: 'SimSun（宋体）', value: 'SimSun' },
  { label: 'KaiTi（楷体）', value: 'KaiTi' },
  { label: 'Arial', value: 'Arial' },
  { label: 'Helvetica', value: 'Helvetica' },
  { label: 'Verdana', value: 'Verdana' },
  { label: 'Tahoma', value: 'Tahoma' },
  { label: 'Times New Roman', value: 'Times New Roman' }
]

const loading = ref(false)
const savingRule = ref(false)
const savingPreference = ref(false)
const testing = ref(false)
const drawerVisible = ref(false)
const importDialogVisible = ref(false)
const sampleEditorDialogVisible = ref(false)
const versionDialogVisible = ref(false)
const importing = ref(false)
const versionLoading = ref(false)
const editingId = ref(null)
const versionRule = ref(null)
const rules = ref([])
const auditLogs = ref([])
const ruleVersions = ref([])
const chartRef = ref(null)
const chart = ref(null)
const testResult = ref(null)
const previewAnimationMeta = ref(null)
const importText = ref('')
const importFileName = ref('')
const importPreview = ref(null)
const rulePage = ref(1)
const rulePageSize = ref(10)
let previewRenderVersion = 0

const filters = reactive({ scenarioType: '', enabled: null, keyword: '' })
const auditFilters = reactive({ action: '', ruleId: '', timeRange: [] })
const form = reactive({
  ruleCode: '',
  ruleName: '',
  scenarioType: 'TIME_SERIES',
  chartType: 'line',
  enabled: true,
  priority: 100,
  matchConfigText: '{}',
  renderConfigText: '{}',
  explainTemplate: ''
})
const structuredMatch = reactive({
  timeRequired: false,
  numericRequired: false,
  dimensionRequired: false,
  fieldKeywordsText: '',
  intentKeywordsText: '',
  industryTags: [],
  metricMin: 0,
  metricMax: 0,
  dimensionMin: 0,
  dimensionMax: 0,
  rowMin: 0,
  rowMax: 0
})
const structuredRender = reactive({
  animation: true,
  tooltip: true,
  dataZoom: false,
  refreshIntervalSeconds: 0,
  autoDataZoomThreshold: 14,
  autoLegendScrollThreshold: 10,
  incrementalRendering: false,
  progressive: 400,
  progressiveThreshold: 3000,
  largeThreshold: 2000
})
const structuredPrediction = reactive({
  enabled: false,
  confidence: 0.95,
  horizon: 3,
  algorithm: 'Holt-Winters',
  showExplanation: true,
  showHistory: true,
  historyLabel: '历史值',
  showForecast: true,
  forecastLabel: '预测值',
  showUpper: true,
  upperLabel: '置信上界',
  showLower: true,
  lowerLabel: '置信下界',
  showAnomaly: false,
  anomalyLabel: '异常点'
})
const predictionLegendItems = [
  { key: 'history', name: '历史区间', showKey: 'showHistory', labelKey: 'historyLabel' },
  { key: 'forecast', name: '预测区间', showKey: 'showForecast', labelKey: 'forecastLabel' },
  { key: 'upper', name: '置信上界', showKey: 'showUpper', labelKey: 'upperLabel' },
  { key: 'lower', name: '置信下界', showKey: 'showLower', labelKey: 'lowerLabel' },
  { key: 'anomaly', name: '异常点', showKey: 'showAnomaly', labelKey: 'anomalyLabel' }
]
const structuredVoice = reactive({
  enabled: true,
  order: ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
  currentTemplate: ''
})
const voiceFieldOptions = [
  { label: '标题', value: 'title' },
  { label: '核心指标', value: 'metric' },
  { label: '最大值', value: 'max' },
  { label: '最小值', value: 'min' },
  { label: '趋势', value: 'trend' },
  { label: '异常点', value: 'anomaly' }
]
const defaultVoiceTemplates = {
  line: '查询完成，已生成折线图。当前按{dimension}分析{metric}，整体趋势为{trend}，最大值为{maxName}{maxValue}。',
  bar: '查询完成，已生成柱状图。当前按{dimension}对比{metric}，最大值为{maxName}{maxValue}，最小值为{minName}{minValue}。',
  pie: '查询完成，已生成饼图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。',
  doughnut: '查询完成，已生成环形图。当前展示{metric}的占比结构，最高项为{maxName}{maxValue}。',
  table: '查询完成，已生成表格。当前展示{count}行明细数据，包含{dimension}和{metric}等字段。'
}
const preferences = reactive({
  themeName: '企业默认可视化风格',
  colorPalette: ['#2563eb', '#16a34a', '#f59e0b', '#dc2626'],
  fontConfig: { fontFamily: 'Microsoft YaHei', fontSize: 12 },
  layoutConfig: { legend: 'top', height: 360 },
  defaultOptions: { animation: true, dataZoom: false, voiceSummary: true }
})
const samplePresetMap = {
  compare: {
    intent: '查看各部门销售额对比',
    fields: [
      { name: '部门', type: 'string' },
      { name: '销售额', type: 'number' }
    ],
    rows: [
      { 部门: '华东', 销售额: 1280 },
      { 部门: '华南', 销售额: 960 },
      { 部门: '华北', 销售额: 1180 }
    ]
  },
  trend: {
    intent: '查看每月销售额趋势',
    fields: [
      { name: '月份', type: 'date' },
      { name: '销售额', type: 'number' }
    ],
    rows: [
      { 月份: '2026-01', 销售额: 820 },
      { 月份: '2026-02', 销售额: 960 },
      { 月份: '2026-03', 销售额: 1130 },
      { 月份: '2026-04', 销售额: 1260 }
    ]
  },
  ratio: {
    intent: '查看各品类销售额占比',
    fields: [
      { name: '品类', type: 'string' },
      { name: '销售额', type: 'number' }
    ],
    rows: [
      { 品类: '办公用品', 销售额: 460 },
      { 品类: '家具', 销售额: 320 },
      { 品类: '技术产品', 销售额: 520 }
    ]
  },
  detail: {
    intent: '查看销售明细，显示客户、城市、产品名称、销售额、数量、折扣、利润',
    fields: [
      { name: '客户', type: 'string' },
      { name: '城市', type: 'string' },
      { name: '产品名称', type: 'string' },
      { name: '销售额', type: 'number' },
      { name: '数量', type: 'number' },
      { name: '折扣', type: 'number' },
      { name: '利润', type: 'number' }
    ],
    rows: [
      { 客户: '张三', 城市: '上海', 产品名称: '文件柜', 销售额: 1280, 数量: 2, 折扣: 0.9, 利润: 260 },
      { 客户: '李四', 城市: '广州', 产品名称: '办公椅', 销售额: 760, 数量: 4, 折扣: 0.85, 利润: 120 }
    ]
  },
  forecast: {
    intent: '预测未来三个月销售额趋势',
    fields: [
      { name: '月份', type: 'date' },
      { name: '销售额', type: 'number' },
      { name: '预测值', type: 'number' },
      { name: '置信上界', type: 'number' },
      { name: '置信下界', type: 'number' }
    ],
    rows: [
      { 月份: '2026-01', 销售额: 820, 预测值: null, 置信上界: null, 置信下界: null },
      { 月份: '2026-02', 销售额: 960, 预测值: null, 置信上界: null, 置信下界: null },
      { 月份: '2026-03', 销售额: 1130, 预测值: 1120, 置信上界: 1220, 置信下界: 1020 },
      { 月份: '2026-04', 销售额: 1260, 预测值: 1240, 置信上界: 1370, 置信下界: 1110 }
    ]
  }
}
const samplePresets = [
  { key: 'compare', label: '分组对比' },
  { key: 'trend', label: '时序趋势' },
  { key: 'ratio', label: '占比分析' },
  { key: 'detail', label: '明细数据' },
  { key: 'forecast', label: '预测样例' }
]
const tester = reactive({
  intent: '查看各部门销售额对比',
  json: JSON.stringify(samplePresetMap.compare, null, 2)
})
const sampleEditor = reactive({
  fields: samplePresetMap.compare.fields.map((field, index) => ({ ...field, id: Date.now() + index })),
  rows: samplePresetMap.compare.rows.map((row) => ({ ...row }))
})

const addPaletteColor = () => {
  const usedColors = new Set(preferences.colorPalette.map((item) => String(item || '').toLowerCase()))
  const nextColor = paletteSuggestions.find((item) => !usedColors.has(item.toLowerCase()))
    || paletteSuggestions[preferences.colorPalette.length % paletteSuggestions.length]
  preferences.colorPalette.push(nextColor)
}

const removePaletteColor = (index) => {
  if (preferences.colorPalette.length <= 1) {
    ElMessage.warning('颜色盘至少保留 1 个颜色')
    return
  }
  preferences.colorPalette.splice(index, 1)
}

const activeSampleFields = computed(() => sampleEditor.fields.filter((field) => String(field.name || '').trim()))

const normalizeSampleValue = (value, type) => {
  if (type !== 'number') return value
  if (value === '' || value == null) return null
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : value
}

const buildSamplePayload = () => ({
  fields: activeSampleFields.value.map((field) => ({
    name: String(field.name || '').trim(),
    type: field.type || 'string'
  })),
  rows: sampleEditor.rows.map((row) => {
    const nextRow = {}
    activeSampleFields.value.forEach((field) => {
      nextRow[field.name] = normalizeSampleValue(row[field.name], field.type)
    })
    return nextRow
  })
})

const syncSampleJsonFromEditor = () => {
  tester.json = JSON.stringify(buildSamplePayload(), null, 2)
}

const syncSampleEditorFromJson = () => {
  try {
    const payload = parseJson(tester.json, { fields: [], rows: [] })
    if (!Array.isArray(payload.fields) || !Array.isArray(payload.rows)) {
      throw new Error('样例 JSON 必须包含 fields 和 rows 数组')
    }
    sampleEditor.fields = payload.fields.map((field, index) => ({
      id: Date.now() + index,
      name: String(field.name || ''),
      type: ['string', 'number', 'date'].includes(field.type) ? field.type : 'string'
    }))
    sampleEditor.rows = payload.rows.map((row) => ({ ...row }))
    syncSampleJsonFromEditor()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '样例 JSON 格式不正确'))
  }
}

const applySamplePreset = (key) => {
  const preset = samplePresetMap[key]
  if (!preset) return
  tester.intent = preset.intent
  sampleEditor.fields = preset.fields.map((field, index) => ({ ...field, id: Date.now() + index }))
  sampleEditor.rows = preset.rows.map((row) => ({ ...row }))
  syncSampleJsonFromEditor()
}

const addSampleField = () => {
  const nextIndex = sampleEditor.fields.length + 1
  const field = { id: Date.now(), name: `字段${nextIndex}`, type: 'string' }
  sampleEditor.fields.push(field)
  sampleEditor.rows.forEach((row) => {
    row[field.name] = ''
  })
  syncSampleJsonFromEditor()
}

const removeSampleField = (index) => {
  if (sampleEditor.fields.length <= 1) {
    ElMessage.warning('至少保留 1 个字段')
    return
  }
  const [removed] = sampleEditor.fields.splice(index, 1)
  if (removed?.name) {
    sampleEditor.rows.forEach((row) => {
      delete row[removed.name]
    })
  }
  syncSampleJsonFromEditor()
}

const addSampleRow = () => {
  const row = {}
  activeSampleFields.value.forEach((field) => {
    row[field.name] = field.type === 'number' ? 0 : ''
  })
  sampleEditor.rows.push(row)
  syncSampleJsonFromEditor()
}

const removeSampleRow = (index) => {
  sampleEditor.rows.splice(index, 1)
  syncSampleJsonFromEditor()
}

const resetAuditFilters = () => {
  Object.assign(auditFilters, { action: '', ruleId: '', timeRange: [] })
}

const normalizeSnapshot = (value) => {
  if (!value) return {}
  if (typeof value === 'object') return value
  try {
    return JSON.parse(String(value))
  } catch {
    return { raw: String(value) }
  }
}

const formatSnapshotValue = (key, value) => {
  if (value == null || value === '') return ''
  if (key === 'scenarioType') return scenarioLabel(value)
  if (key === 'chartType') return chartTypeLabel(value)
  if (key === 'enabled') return value ? '启用' : '禁用'
  if (key === 'colorPalette' && Array.isArray(value)) return `${value.length} 个颜色`
  if (key === 'fontConfig' && typeof value === 'object') {
    return [value.fontFamily, value.fontSize ? `${value.fontSize}px` : ''].filter(Boolean).join(' / ')
  }
  if (key === 'layoutConfig' && typeof value === 'object') {
    return [value.legend ? `图例${value.legend}` : '', value.height ? `${value.height}px` : ''].filter(Boolean).join(' / ')
  }
  if (key === 'defaultOptions' && typeof value === 'object') {
    return Object.entries(value).filter(([, enabled]) => enabled).map(([name]) => name).slice(0, 3).join('、') || '未启用'
  }
  if (Array.isArray(value)) return value.slice(0, 3).join('、') + (value.length > 3 ? `等${value.length}项` : '')
  if (typeof value === 'object') return Object.keys(value).slice(0, 3).join('、') || '已配置'
  return String(value)
}

const auditActionLabel = (value) => auditActionLabels[value] || value || '-'
const auditActionTagClass = (value) => ['audit-action-tag', auditActionTagClassMap[value] || 'audit-action-tag--default']

const buildAuditSnapshotSource = (row) => {
  const snapshot = normalizeSnapshot(row?.afterSnapshot)
  const profile = normalizeSnapshot(snapshot.profile)
  const matchedRule = normalizeSnapshot(snapshot.matchedRule)
  return {
    ...snapshot,
    ...(Object.keys(profile).length ? {
      rowCount: profile.rowCount,
      fieldCount: profile.fieldCount,
      metricFields: profile.metricFields,
      dimensionFields: profile.dimensionFields
    } : {}),
    ...(Object.keys(matchedRule).length ? {
      ruleName: matchedRule.ruleName || snapshot.ruleName,
      ruleCode: matchedRule.ruleCode || snapshot.ruleCode,
      scenarioType: matchedRule.scenarioType || snapshot.scenarioType,
      chartType: matchedRule.chartType || snapshot.chartType
    } : {})
  }
}

const compactListText = (value, suffix = '项') => {
  if (!Array.isArray(value)) return ''
  if (!value.length) return ''
  return value.slice(0, 3).join('、') + (value.length > 3 ? `等${value.length}${suffix}` : '')
}

const auditChangeDescription = (row) => {
  const source = buildAuditSnapshotSource(row)
  const action = row?.action
  const ruleName = source.ruleName || source.ruleCode || (row?.ruleId ? `规则ID ${row.ruleId}` : '规则')
  const ruleCode = source.ruleCode ? `（${source.ruleCode}）` : ''
  const scenario = source.scenarioType ? scenarioLabel(source.scenarioType) : ''
  const chartType = source.chartType ? chartTypeLabel(source.chartType) : ''
  const sceneChartText = [scenario, chartType].filter(Boolean).join('，')
  const sceneSuffix = sceneChartText ? `，场景为${sceneChartText}` : ''

  if (action === 'TEST') {
    const rowCount = source.rowCount != null ? `${source.rowCount} 行` : ''
    const fieldCount = source.fieldCount != null ? `${source.fieldCount} 字段` : ''
    const sampleText = [rowCount, fieldCount].filter(Boolean).join('、')
    return `测试推荐命中${ruleName}${ruleCode}${sceneSuffix}${sampleText ? `，样例数据 ${sampleText}` : ''}。`
  }
  if (action === 'PREFERENCE_UPDATE') {
    const paletteText = Array.isArray(source.colorPalette) ? `${source.colorPalette.length} 个颜色` : ''
    const fontText = formatSnapshotValue('fontConfig', source.fontConfig)
    const layoutText = formatSnapshotValue('layoutConfig', source.layoutConfig)
    const detailText = [
      source.themeName ? `主题为「${source.themeName}」` : '',
      paletteText ? `颜色盘包含 ${paletteText}` : '',
      fontText ? `字体为 ${fontText}` : '',
      layoutText ? `布局为 ${layoutText}` : ''
    ].filter(Boolean).join('，')
    return `更新企业图表偏好${detailText ? `：${detailText}` : ''}。`
  }
  if (action === 'CREATE' || action === 'INIT') {
    return `${auditActionLabel(action)}「${ruleName}」${ruleCode}${sceneSuffix}。`
  }
  if (action === 'UPDATE') {
    const priorityText = source.priority != null ? `，优先级为 ${source.priority}` : ''
    return `更新「${ruleName}」${ruleCode}${sceneSuffix}${priorityText}。`
  }
  if (action === 'DELETE') {
    return `删除「${ruleName}」${ruleCode}${sceneSuffix}。`
  }
  if (action === 'ENABLE' || action === 'DISABLE') {
    return `${action === 'ENABLE' ? '启用' : '禁用'}「${ruleName}」${ruleCode}${sceneSuffix}。`
  }
  if (action === 'ROLLBACK') {
    return `将「${ruleName}」${ruleCode}回滚到历史版本${sceneSuffix}。`
  }
  if (action === 'IMPORT') {
    return `导入图表推荐规则配置${source.ruleCount ? `，涉及 ${source.ruleCount} 条规则` : ''}。`
  }
  const metrics = compactListText(source.metricFields)
  const dimensions = compactListText(source.dimensionFields)
  const extra = [
    metrics ? `指标：${metrics}` : '',
    dimensions ? `维度：${dimensions}` : ''
  ].filter(Boolean).join('；')
  if (ruleName !== '规则' || sceneChartText || extra) {
    return `${auditActionLabel(action)}「${ruleName}」${ruleCode}${sceneSuffix}${extra ? `，${extra}` : ''}。`
  }
  if (source.raw) return `记录了变更内容：${source.raw}`
  return '记录了一次配置变更。'
}

const withPreviewLayout = (option = {}) => {
  const nextOption = { ...option }
  const title = Array.isArray(nextOption.title) ? nextOption.title[0] : nextOption.title
  if (title) {
    nextOption.title = {
      ...title,
      left: title.left ?? 'center',
      top: title.top ?? 14,
      textStyle: {
        fontSize: 16,
        fontWeight: 700,
        overflow: 'truncate',
        width: 220,
        ...(title.textStyle || {})
      }
    }
  }
  if (nextOption.legend) {
    const legend = Array.isArray(nextOption.legend) ? nextOption.legend[0] : nextOption.legend
    nextOption.legend = {
      ...legend,
      top: legend.top ?? 44,
      left: legend.left ?? 'center',
      type: legend.type ?? 'scroll'
    }
  }
  if (nextOption.grid || ['line', 'bar'].includes(testResult.value?.chartType)) {
    const grid = Array.isArray(nextOption.grid) ? nextOption.grid[0] : nextOption.grid
    nextOption.grid = {
      ...(grid || {}),
      left: 48,
      right: 20,
      top: nextOption.legend ? 94 : 72,
      bottom: 42,
      containLabel: true
    }
  }
  return nextOption
}

const versionDialogTitle = computed(() => `规则版本：${versionRule.value?.ruleName || ''}`)
const latestVersionNo = computed(() => Math.max(0, ...ruleVersions.value.map((item) => Number(item.versionNo) || 0)))
const totalRuleCount = computed(() => rules.value.length)
const enabledRuleCount = computed(() => rules.value.filter((item) => item.enabled).length)
const enabledRuleRate = computed(() => {
  if (!totalRuleCount.value) return 0
  return Math.round((enabledRuleCount.value / totalRuleCount.value) * 100)
})
const scenarioCount = computed(() => new Set(rules.value.map((item) => item.scenarioType).filter(Boolean)).size)
const timeSeriesRuleCount = computed(() => rules.value.filter((item) => item.scenarioType === 'TIME_SERIES').length)
const highPriorityRuleCount = computed(() => rules.value.filter((item) => Number(item.priority) >= 800).length)
const pagedRules = computed(() => {
  const start = (rulePage.value - 1) * rulePageSize.value
  return rules.value.slice(start, start + rulePageSize.value)
})
const canPreviewImport = computed(() => Boolean(importText.value.trim()) && !importing.value)
const auditActionOptions = computed(() => Array.from(new Set(auditLogs.value.map((row) => row.action).filter(Boolean)))
  .map((value) => ({ label: auditActionLabel(value), value }))
  .sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN')))
const filteredAuditLogs = computed(() => {
  const actionValue = auditFilters.action
  const ruleIdKeyword = auditFilters.ruleId.trim().toLowerCase()
  const [startTime, endTime] = Array.isArray(auditFilters.timeRange) ? auditFilters.timeRange : []
  const start = startTime ? new Date(startTime).getTime() : null
  const end = endTime ? new Date(endTime).getTime() : null
  return auditLogs.value.filter((row) => {
    const ruleId = String(row.ruleId ?? '').toLowerCase()
    const createdAt = row.createdAt ? new Date(row.createdAt).getTime() : null
    if (actionValue && row.action !== actionValue) return false
    if (ruleIdKeyword && !ruleId.includes(ruleIdKeyword)) return false
    if (start && (!createdAt || createdAt < start)) return false
    if (end && (!createdAt || createdAt > end)) return false
    return true
  })
})
const scenarioLabel = (value) => scenarioSelectOptions.find((item) => item.value === value)?.label || value
const scenarioTagClass = (value) => ['scenario-tag', scenarioTagClassMap[value] || 'scenario-tag--default']
const chartTypeLabel = (value) => chartTypeLabels[value] || value || '-'
const splitKeywords = (value) => String(value || '').split(/[，,;；、\s]+/).map((item) => item.trim()).filter(Boolean)
const parseJson = (text, fallback = {}) => {
  try {
    return JSON.parse(text || '{}')
  } catch {
    throw new Error('JSON 格式不正确')
  }
}

const friendlyConfigError = (error, fallback = '操作失败') => {
  const raw = String(error?.message || fallback || '操作失败').trim()
  if (!raw) return fallback
  if (raw.includes('JSON config is invalid') || raw.includes('Invalid JSON config')) {
    return 'JSON 格式不正确，请检查逗号、引号和括号是否完整。'
  }
  if (raw.includes('JSON config is too large')) {
    return '配置 JSON 过大，请删减样例或拆分配置后再保存。'
  }
  if (raw.includes('JSON config list is too large')) {
    return '配置数组过长，请减少列表项数量后再保存。'
  }
  if (raw.includes('JSON config text is too long')) {
    return raw.replace('JSON config text is too long:', '配置文本过长：')
  }
  if (raw.includes('JSON config contains unsafe key:')) {
    return raw.replace('JSON config contains unsafe key:', '检测到不安全配置字段：')
      + '，禁止配置脚本、函数入口或外链地址字段。'
  }
  if (raw.includes('JSON config contains unsafe content:')) {
    return raw.replace('JSON config contains unsafe content:', '检测到不安全配置内容：')
      + '，禁止在图表配置中写入脚本、函数体或外链 URL。'
  }
  if (raw.includes('不支持的 ECharts 渲染配置字段:')) {
    return raw.replace('不支持的 ECharts 渲染配置字段:', '暂不支持该图表渲染配置字段：')
      + '。请使用页面中的结构化配置项。'
  }
  if (raw.includes('不支持的 ECharts 渲染配置节点:')) {
    return raw.replace('不支持的 ECharts 渲染配置节点:', '暂不支持该图表渲染配置节点：')
      + '。请使用页面中的结构化配置项。'
  }
  if (raw.includes('ECharts 渲染配置数组过长:')) {
    return raw.replace('ECharts 渲染配置数组过长:', '图表渲染配置数组过长：')
  }
  return raw
}

const resetStructuredMatch = () => {
  Object.assign(structuredMatch, {
    timeRequired: false,
    numericRequired: false,
    dimensionRequired: false,
    fieldKeywordsText: '',
    intentKeywordsText: '',
    industryTags: [],
    metricMin: 0,
    metricMax: 0,
    dimensionMin: 0,
    dimensionMax: 0,
    rowMin: 0,
    rowMax: 0
  })
}

const syncJsonToStructuredMatch = () => {
  try {
    const config = parseJson(form.matchConfigText, {})
    Object.assign(structuredMatch, {
      timeRequired: Boolean(config.timeRequired),
      numericRequired: Boolean(config.numericRequired),
      dimensionRequired: Boolean(config.dimensionRequired),
      fieldKeywordsText: Array.isArray(config.fieldKeywords) ? config.fieldKeywords.join(',') : '',
      intentKeywordsText: Array.isArray(config.intentKeywords) ? config.intentKeywords.join(',') : String(config.keyword || ''),
      industryTags: Array.isArray(config.industryTags) ? config.industryTags : [],
      metricMin: Number(config.metricMin || 0),
      metricMax: Number(config.metricMax || 0),
      dimensionMin: Number(config.dimensionMin || 0),
      dimensionMax: Number(config.dimensionMax || 0),
      rowMin: Number(config.rowMin || 0),
      rowMax: Number(config.rowMax || 0)
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredMatchToJson = () => {
  const current = parseJson(form.matchConfigText, {})
  const next = { ...current }
  next.timeRequired = structuredMatch.timeRequired
  next.numericRequired = structuredMatch.numericRequired
  next.dimensionRequired = structuredMatch.dimensionRequired
  const fieldKeywords = splitKeywords(structuredMatch.fieldKeywordsText)
  const intentKeywords = splitKeywords(structuredMatch.intentKeywordsText)
  if (fieldKeywords.length) next.fieldKeywords = fieldKeywords
  else delete next.fieldKeywords
  if (intentKeywords.length) next.intentKeywords = intentKeywords
  else delete next.intentKeywords
  if (structuredMatch.industryTags.length) next.industryTags = structuredMatch.industryTags
  else delete next.industryTags
  for (const key of ['metricMin', 'metricMax', 'dimensionMin', 'dimensionMax', 'rowMin', 'rowMax']) {
    const value = Number(structuredMatch[key] || 0)
    if (value > 0) next[key] = value
    else delete next[key]
  }
  form.matchConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredDynamicRender = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const tooltip = config.tooltip && typeof config.tooltip === 'object' ? config.tooltip : {}
    const dataZoom = config.dataZoom && typeof config.dataZoom === 'object' && !Array.isArray(config.dataZoom)
      ? config.dataZoom
      : {}
    const dynamic = config.dynamic && typeof config.dynamic === 'object' ? config.dynamic : {}
    Object.assign(structuredRender, {
      animation: config.animation == null ? true : Boolean(config.animation),
      tooltip: config.tooltip == null ? true : (typeof config.tooltip === 'object' ? tooltip.show !== false : Boolean(config.tooltip)),
      dataZoom: config.dataZoom == null ? false : (typeof config.dataZoom === 'object' ? dataZoom.enabled !== false : Boolean(config.dataZoom)),
      refreshIntervalSeconds: Number(dynamic.refreshIntervalSeconds ?? config.refreshIntervalSeconds ?? config.dynamicRefreshInterval ?? 0),
      autoDataZoomThreshold: Number(dynamic.autoDataZoomThreshold ?? dataZoom.threshold ?? 14),
      autoLegendScrollThreshold: Number(dynamic.autoLegendScrollThreshold ?? 10),
      incrementalRendering: Boolean(dynamic.incrementalRendering),
      progressive: Number(dynamic.progressive || 400),
      progressiveThreshold: Number(dynamic.progressiveThreshold || 3000),
      largeThreshold: Number(dynamic.largeThreshold || 2000)
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredRenderToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const previousTooltip = next.tooltip && typeof next.tooltip === 'object' ? next.tooltip : {}
  const previousDataZoom = next.dataZoom && typeof next.dataZoom === 'object' && !Array.isArray(next.dataZoom)
    ? next.dataZoom
    : {}
  const previousDynamic = next.dynamic && typeof next.dynamic === 'object' ? next.dynamic : {}
  next.animation = structuredRender.animation
  next.tooltip = {
    ...previousTooltip,
    show: structuredRender.tooltip,
    confine: true
  }
  next.dataZoom = {
    ...previousDataZoom,
    enabled: structuredRender.dataZoom,
    threshold: Number(structuredRender.autoDataZoomThreshold || 14),
    start: previousDataZoom.start ?? 0,
    end: previousDataZoom.end ?? 60
  }
  next.dynamic = {
    ...previousDynamic,
    refreshIntervalSeconds: Number(structuredRender.refreshIntervalSeconds || 0),
    incrementalRendering: structuredRender.incrementalRendering,
    progressive: Number(structuredRender.progressive || 0),
    progressiveThreshold: Number(structuredRender.progressiveThreshold || 3000),
    largeThreshold: Number(structuredRender.largeThreshold || 2000),
    autoDataZoomThreshold: Number(structuredRender.autoDataZoomThreshold || 14),
    autoLegendScrollThreshold: Number(structuredRender.autoLegendScrollThreshold || 10)
  }
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const defaultPredictionLegend = {
  history: { show: true, label: '历史值' },
  forecast: { show: true, label: '预测值' },
  upper: { show: true, label: '置信上界' },
  lower: { show: true, label: '置信下界' },
  anomaly: { show: false, label: '异常点' }
}

const normalizePredictionLabel = (legendConfig, key, fallback) => {
  const item = legendConfig?.[key]
  return String(item?.label || fallback).trim() || fallback
}

const normalizePredictionShow = (legendConfig, key, fallback) => {
  const item = legendConfig?.[key]
  return item?.show == null ? fallback : Boolean(item.show)
}

const syncJsonToStructuredPrediction = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const prediction = config.prediction && typeof config.prediction === 'object' ? config.prediction : {}
    const legendConfig = prediction.legendConfig && typeof prediction.legendConfig === 'object'
      ? prediction.legendConfig
      : defaultPredictionLegend
    const legacyLegend = Array.isArray(prediction.legend) ? prediction.legend : []
    Object.assign(structuredPrediction, {
      enabled: Boolean(prediction.enabled),
      confidence: Number(prediction.confidence || 0.95),
      horizon: Number(prediction.horizon || 3),
      algorithm: String(prediction.algorithm || 'Holt-Winters'),
      showExplanation: prediction.showExplanation == null ? true : Boolean(prediction.showExplanation),
      showHistory: normalizePredictionShow(legendConfig, 'history', true),
      historyLabel: normalizePredictionLabel(legendConfig, 'history', legacyLegend[0] || '历史值'),
      showForecast: normalizePredictionShow(legendConfig, 'forecast', true),
      forecastLabel: normalizePredictionLabel(legendConfig, 'forecast', legacyLegend[1] || '预测值'),
      showUpper: normalizePredictionShow(legendConfig, 'upper', true),
      upperLabel: normalizePredictionLabel(legendConfig, 'upper', legacyLegend[2] || '置信上界'),
      showLower: normalizePredictionShow(legendConfig, 'lower', true),
      lowerLabel: normalizePredictionLabel(legendConfig, 'lower', legacyLegend[3] || '置信下界'),
      showAnomaly: normalizePredictionShow(legendConfig, 'anomaly', false),
      anomalyLabel: normalizePredictionLabel(legendConfig, 'anomaly', legacyLegend[4] || '异常点')
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredPredictionToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const prediction = {
    ...(next.prediction && typeof next.prediction === 'object' ? next.prediction : {}),
    enabled: structuredPrediction.enabled,
    confidence: Number(structuredPrediction.confidence || 0.95),
    horizon: Number(structuredPrediction.horizon || 3),
    algorithm: String(structuredPrediction.algorithm || 'Holt-Winters').trim() || 'Holt-Winters',
    showExplanation: structuredPrediction.showExplanation,
    legendConfig: {
      history: { show: structuredPrediction.showHistory, label: structuredPrediction.historyLabel || '历史值' },
      forecast: { show: structuredPrediction.showForecast, label: structuredPrediction.forecastLabel || '预测值' },
      upper: { show: structuredPrediction.showUpper, label: structuredPrediction.upperLabel || '置信上界' },
      lower: { show: structuredPrediction.showLower, label: structuredPrediction.lowerLabel || '置信下界' },
      anomaly: { show: structuredPrediction.showAnomaly, label: structuredPrediction.anomalyLabel || '异常点' }
    }
  }
  prediction.legend = Object.values(prediction.legendConfig)
    .filter(item => item.show)
    .map(item => item.label)
  next.prediction = prediction
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredVoice = () => {
  try {
    const config = parseJson(form.renderConfigText, {})
    const raw = config.voiceSummary
    const voice = raw && typeof raw === 'object' ? raw : { enabled: raw == null ? true : Boolean(raw) }
    const chartTemplates = voice.chartTemplates && typeof voice.chartTemplates === 'object' ? voice.chartTemplates : {}
    const hasChartTemplate = Object.prototype.hasOwnProperty.call(chartTemplates, form.chartType)
    const templateValue = hasChartTemplate
      ? chartTemplates[form.chartType]
      : (voice.summaryTemplate ?? defaultVoiceTemplates[form.chartType] ?? defaultVoiceTemplates.bar)
    Object.assign(structuredVoice, {
      enabled: voice.enabled == null ? true : Boolean(voice.enabled),
      order: Array.isArray(voice.order) && voice.order.length ? voice.order.map(String) : ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
      currentTemplate: String(templateValue ?? '')
    })
  } catch {
    // 高级 JSON 允许临时编辑为非法状态，保存时统一校验。
  }
}

const syncStructuredVoiceToJson = () => {
  const current = parseJson(form.renderConfigText, {})
  const next = { ...current }
  const previous = next.voiceSummary && typeof next.voiceSummary === 'object' ? next.voiceSummary : {}
  const chartTemplates = {
    ...(previous.chartTemplates && typeof previous.chartTemplates === 'object' ? previous.chartTemplates : {}),
    [form.chartType]: structuredVoice.currentTemplate ?? ''
  }
  next.voiceSummary = {
    ...previous,
    enabled: structuredVoice.enabled,
    order: structuredVoice.order?.length ? structuredVoice.order : ['title', 'metric', 'max', 'min', 'trend', 'anomaly'],
    chartTemplates
  }
  form.renderConfigText = JSON.stringify(next, null, 2)
}

const syncJsonToStructuredRenderConfig = () => {
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
}

const loadRules = async () => {
  loading.value = true
  try {
    rules.value = await fetchChartRules({
      scenarioType: filters.scenarioType || undefined,
      enabled: filters.enabled ?? undefined,
      keyword: filters.keyword || undefined
    })
    rulePage.value = 1
  } catch (error) {
    ElMessage.error(error.message || '加载规则失败')
  } finally {
    loading.value = false
  }
}

const loadPreferences = async () => {
  try {
    const data = await fetchChartPreferences()
    Object.assign(preferences, {
      themeName: data.themeName || preferences.themeName,
      colorPalette: Array.isArray(data.colorPalette) ? data.colorPalette : preferences.colorPalette,
      fontConfig: { ...preferences.fontConfig, ...(data.fontConfig || {}) },
      layoutConfig: { ...preferences.layoutConfig, ...(data.layoutConfig || {}) },
      defaultOptions: { ...preferences.defaultOptions, ...(data.defaultOptions || {}) }
    })
  } catch (error) {
    ElMessage.error(error.message || '加载图表偏好失败')
  }
}

const loadAuditLogs = async () => {
  try {
    auditLogs.value = await fetchChartRuleAuditLogs({ limit: 50 })
  } catch (error) {
    ElMessage.error(error.message || '加载审计日志失败')
  }
}

const loadAll = async () => {
  await Promise.all([loadRules(), loadPreferences(), loadAuditLogs()])
}

const resetForm = () => {
  resetStructuredMatch()
  Object.assign(form, {
    ruleCode: '',
    ruleName: '',
    scenarioType: 'TIME_SERIES',
    chartType: 'line',
    enabled: true,
    priority: 100,
    matchConfigText: '{\n  "timeRequired": true,\n  "numericRequired": true\n}',
    renderConfigText: '{\n  "animation": true,\n  "smooth": true,\n  "tooltip": {\n    "show": true,\n    "confine": true\n  },\n  "dataZoom": {\n    "enabled": false,\n    "threshold": 14,\n    "start": 0,\n    "end": 60\n  },\n  "dynamic": {\n    "refreshIntervalSeconds": 0,\n    "incrementalRendering": false,\n    "progressive": 400,\n    "progressiveThreshold": 3000,\n    "largeThreshold": 2000,\n    "autoDataZoomThreshold": 14,\n    "autoLegendScrollThreshold": 10\n  },\n  "voiceSummary": {\n    "enabled": true,\n    "order": ["title", "metric", "max", "min", "trend", "anomaly"],\n    "chartTemplates": {\n      "line": "查询完成，已生成折线图。当前按{dimension}分析{metric}，整体趋势为{trend}，最大值为{maxName}{maxValue}。"\n    }\n  },\n  "prediction": {\n    "enabled": true,\n    "confidence": 0.95,\n    "horizon": 3,\n    "algorithm": "Holt-Winters",\n    "showExplanation": true,\n    "legendConfig": {\n      "history": { "show": true, "label": "历史值" },\n      "forecast": { "show": true, "label": "预测值" },\n      "upper": { "show": true, "label": "置信上界" },\n      "lower": { "show": true, "label": "置信下界" },\n      "anomaly": { "show": false, "label": "异常点" }\n    }\n  }\n}',
    explainTemplate: ''
  })
  syncJsonToStructuredMatch()
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
}

const openCreate = () => {
  editingId.value = null
  resetForm()
  drawerVisible.value = true
}

const openEdit = (row) => {
  editingId.value = row.id
  Object.assign(form, {
    ruleCode: row.ruleCode,
    ruleName: row.ruleName,
    scenarioType: row.scenarioType,
    chartType: row.chartType,
    enabled: !!row.enabled,
    priority: Number(row.priority) || 0,
    matchConfigText: JSON.stringify(row.matchConfig || {}, null, 2),
    renderConfigText: JSON.stringify(row.renderConfig || {}, null, 2),
    explainTemplate: row.explainTemplate || ''
  })
  syncJsonToStructuredMatch()
  syncJsonToStructuredDynamicRender()
  syncJsonToStructuredVoice()
  syncJsonToStructuredPrediction()
  drawerVisible.value = true
}

const saveRule = async () => {
  savingRule.value = true
  try {
    syncStructuredMatchToJson()
    syncStructuredRenderToJson()
    syncStructuredVoiceToJson()
    if (form.chartType === 'line') {
      syncStructuredPredictionToJson()
    }
    const payload = {
      ruleCode: form.ruleCode.trim(),
      ruleName: form.ruleName.trim(),
      scenarioType: form.scenarioType,
      chartType: form.chartType,
      enabled: form.enabled,
      priority: form.priority,
      matchConfig: parseJson(form.matchConfigText),
      renderConfig: parseJson(form.renderConfigText),
      explainTemplate: form.explainTemplate
    }
    if (editingId.value) {
      await updateChartRule(editingId.value, payload)
    } else {
      await createChartRule(payload)
    }
    ElMessage.success('规则已保存')
    drawerVisible.value = false
    await Promise.all([loadRules(), loadAuditLogs()])
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '保存规则失败'))
  } finally {
    savingRule.value = false
  }
}

const toggleRule = async (row) => {
  try {
    await updateChartRuleEnabled(row.id, row.enabled)
    ElMessage.success(row.enabled ? '规则已启用' : '规则已禁用')
    await loadAuditLogs()
  } catch (error) {
    row.enabled = !row.enabled
    ElMessage.error(friendlyConfigError(error, '更新状态失败'))
  }
}

const removeRule = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除规则「${row.ruleName}」吗？`, '删除确认', { type: 'warning' })
    await deleteChartRule(row.id)
    ElMessage.success('规则已删除')
    await Promise.all([loadRules(), loadAuditLogs()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(friendlyConfigError(error, '删除失败'))
  }
}

const loadRuleVersions = async () => {
  if (!versionRule.value?.id) return
  versionLoading.value = true
  try {
    ruleVersions.value = await fetchChartRuleVersions(versionRule.value.id)
  } catch (error) {
    ElMessage.error(error.message || '加载版本失败')
  } finally {
    versionLoading.value = false
  }
}

const openVersions = async (row) => {
  versionRule.value = row
  ruleVersions.value = []
  versionDialogVisible.value = true
  await loadRuleVersions()
}

const rollbackVersion = async (row) => {
  if (!versionRule.value?.id) return
  try {
    await ElMessageBox.confirm(
      `确定将规则「${versionRule.value.ruleName}」回滚到版本 ${row.versionNo} 吗？`,
      '回滚确认',
      { type: 'warning' }
    )
    await rollbackChartRuleVersion(versionRule.value.id, row.id)
    ElMessage.success('规则已回滚')
    await Promise.all([loadRules(), loadAuditLogs(), loadRuleVersions()])
  } catch (error) {
    if (error === 'cancel') return
    ElMessage.error(friendlyConfigError(error, '回滚失败'))
  }
}

const savePreference = async () => {
  savingPreference.value = true
  try {
    await saveChartPreferences({ ...preferences })
    ElMessage.success('图表偏好已保存')
    await loadAuditLogs()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '保存偏好失败'))
  } finally {
    savingPreference.value = false
  }
}

const exportConfig = async () => {
  try {
    const payload = await exportChartRuleConfig()
    const text = JSON.stringify(payload, null, 2)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `ai-chart-rules-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('配置已导出')
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  }
}

const invalidateImportPreview = () => {
  importPreview.value = null
}

const handleImportFile = (file) => {
  const rawFile = file.raw || file
  if (!rawFile?.name?.toLowerCase().endsWith('.json') && rawFile?.type && rawFile.type !== 'application/json') {
    ElMessage.warning('请上传 JSON 配置文件')
    return false
  }
  const reader = new FileReader()
  reader.onload = () => {
    importText.value = String(reader.result || '')
    importFileName.value = rawFile.name || '已选择 JSON 文件'
    importPreview.value = null
    ElMessage.success('JSON 文件已读取，请预览差异后再确认导入')
  }
  reader.onerror = () => {
    ElMessage.error('读取 JSON 文件失败，请重新选择文件')
  }
  reader.readAsText(rawFile, 'utf-8')
  return false
}

const resetImportState = () => {
  importText.value = ''
  importFileName.value = ''
  importPreview.value = null
}

const closeImportDialog = () => {
  importDialogVisible.value = false
  resetImportState()
}

const importActionLabel = (value) => ({
  CREATE: '新增',
  OVERWRITE: '更新',
  UNCHANGED: '跳过',
  CONFLICT: '冲突'
}[value] || value || '-')

const importActionTagType = (value) => ({
  CREATE: 'success',
  OVERWRITE: 'warning',
  UNCHANGED: 'info',
  CONFLICT: 'danger'
}[value] || 'info')

const parseImportPayload = () => {
  const payload = parseJson(importText.value, {})
  if (!Array.isArray(payload.rules)) {
    throw new Error('导入 JSON 缺少 rules 数组')
  }
  return payload
}

const previewImport = async () => {
  importing.value = true
  try {
    importPreview.value = await previewImportChartRuleConfig(parseImportPayload())
    ElMessage.success('差异预览已生成')
  } catch (error) {
    importPreview.value = null
    ElMessage.error(friendlyConfigError(error, '导入预览失败'))
  } finally {
    importing.value = false
  }
}

const applyImport = async () => {
  importing.value = true
  try {
    await importChartRuleConfig(parseImportPayload())
    ElMessage.success('配置已导入')
    closeImportDialog()
    await loadAll()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '导入失败'))
  } finally {
    importing.value = false
  }
}

const runTest = async () => {
  testing.value = true
  try {
    const sample = parseJson(tester.json, { fields: [], rows: [] })
    testResult.value = await testChartRule({ intent: tester.intent, ...sample })
    await nextTick()
    renderPreview()
    await loadAuditLogs()
  } catch (error) {
    ElMessage.error(friendlyConfigError(error, '测试失败'))
  } finally {
    testing.value = false
  }
}

const replayPreviewRenderAnimation = (enabled) => {
  if (!chartRef.value) return
  chartRef.value.classList.remove('chart-preview-animating')
  if (!enabled) return
  void chartRef.value.offsetWidth
  window.requestAnimationFrame(() => {
    chartRef.value?.classList.add('chart-preview-animating')
  })
}

const renderPreview = () => {
  if (!chartRef.value || !testResult.value) return
  const option = testResult.value.option || {}
  if (option.type === 'table') {
    if (chart.value) {
      chart.value.dispose()
      chart.value = null
    }
    previewAnimationMeta.value = null
    chartRef.value.innerHTML = '<div class="table-preview">表格推荐：请查看返回 columns 和 rows。</div>'
    return
  }
  if (testResult.value.chartType === 'line' && hasForecastSeriesRows(option.rows || option.data || parseJson(tester.json, {}).rows)) {
    if (!chart.value || chart.value.isDisposed?.()) {
      chartRef.value.innerHTML = ''
      chart.value = echarts.init(chartRef.value)
    }
    const sample = parseJson(tester.json, { rows: [] })
    const template = testResult.value.matchedRule?.renderConfig?.prediction || {}
    const baseForecastOption = buildForecastChartOption(sample.rows || [], {
      metricLabel: testResult.value.profile?.metric || '预测值',
      confidenceLabel: template.confidence ? `${Math.round(Number(template.confidence) * 100)}%` : '95%',
      legendConfig: template.legendConfig
    })
    const finalOption = withPreviewLayout(
      applyDynamicInteractionDefaults(
        applyOptionTemplateDefaults(baseForecastOption, testResult.value.optionTemplate),
        testResult.value.optionTemplate,
        { chartType: testResult.value.chartType }
      )
    )
    if (finalOption.animation !== false) {
      chart.value.clear()
    }
    previewAnimationMeta.value = getChartAnimationMeta(finalOption)
    const replayStartOption = buildAnimationReplayStartOption(finalOption)
    const renderVersion = ++previewRenderVersion
    if (replayStartOption) {
      chart.value.setOption(replayStartOption, { notMerge: true, lazyUpdate: false })
      chart.value.resize()
      window.setTimeout(() => {
        if (renderVersion !== previewRenderVersion || chart.value?.isDisposed?.()) return
        chart.value.setOption(finalOption, { notMerge: false, lazyUpdate: false })
        chart.value.resize()
      }, 80)
      return
    }
    chart.value.setOption(finalOption, { notMerge: true, lazyUpdate: false })
    chart.value.resize()
    return
  }

  if (!chart.value || chart.value.isDisposed?.()) {
    chartRef.value.innerHTML = ''
    chart.value = echarts.init(chartRef.value)
  }
  const finalOption = withPreviewLayout(
    applyDynamicInteractionDefaults(
      applyOptionTemplateDefaults(option, testResult.value.optionTemplate),
      testResult.value.optionTemplate,
      { chartType: testResult.value.chartType }
    )
  )
  if (finalOption.animation !== false) {
    chart.value.clear()
  }
  previewAnimationMeta.value = getChartAnimationMeta(finalOption)
  const replayStartOption = buildAnimationReplayStartOption(finalOption)
  const renderVersion = ++previewRenderVersion
  if (replayStartOption) {
    chart.value.setOption(replayStartOption, { notMerge: true, lazyUpdate: false })
    chart.value.resize()
    window.setTimeout(() => {
      if (renderVersion !== previewRenderVersion || chart.value?.isDisposed?.()) return
      chart.value.setOption(finalOption, { notMerge: false, lazyUpdate: false })
      chart.value.resize()
    }, 80)
    return
  }
  chart.value.setOption(finalOption, { notMerge: true, lazyUpdate: false })
  chart.value.resize()
}

onMounted(loadAll)
onBeforeUnmount(() => {
  if (chart.value) {
    chart.value.dispose()
  }
})
</script>

<style scoped>
.chart-rule-page {
  min-height: 100%;
  padding: 18px 22px 24px;
  background: #f5f7fb;
}

.chart-rule-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
  color: #13213c;
}

.chart-rule-hero h1 {
  margin: 0;
  color: #101b36;
  font-size: 28px;
  line-height: 1.2;
}

.chart-rule-hero p {
  margin: 8px 0 0;
  color: #59667f;
  font-size: 14px;
  line-height: 1.6;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  min-height: 104px;
  padding: 20px 22px;
  background: #fff;
  border: 1px solid #dfe6f1;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.metric-icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  color: #fff;
  border-radius: 8px;
}

.metric-icon .el-icon {
  font-size: 24px;
}

.metric-blue .metric-icon {
  background: #2563eb;
}

.metric-green .metric-icon {
  background: #16a34a;
}

.metric-purple .metric-icon {
  background: #7c3aed;
}

.metric-orange .metric-icon {
  background: #f97316;
}

.metric-copy {
  min-width: 0;
}

.metric-copy span,
.metric-copy small,
.muted {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.metric-copy strong {
  display: block;
  margin: 5px 0 2px;
  color: #0f172a;
  font-size: 28px;
  line-height: 1;
}

.rule-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 386px;
  gap: 16px;
  align-items: stretch;
}

.workspace-main,
.workspace-side {
  min-width: 0;
}

.workspace-main {
  display: flex;
  flex-direction: column;
}

.panel {
  margin-bottom: 16px;
  overflow: hidden;
  border: 1px solid #dfe6f1;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.panel :deep(.el-card__header) {
  padding: 16px 18px;
  border-bottom-color: #e8edf5;
}

.panel :deep(.el-card__body) {
  padding: 16px 18px 18px;
}

.panel-title,
.palette-row,
.switch-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.panel-title {
  justify-content: space-between;
}

.panel-heading {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  min-width: 0;
}

.panel-heading h3 {
  margin: 0;
  color: #0f172a;
  font-size: 16px;
  line-height: 1.35;
}

.panel-heading p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.compact-heading {
  align-items: flex-start;
}

.audit-head-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.audit-head-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.audit-query-button {
  color: #2563eb;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.audit-query-button:hover,
.audit-query-button:focus {
  color: #1d4ed8;
  background: #dbeafe;
  border-color: #93c5fd;
}

.audit-reset-button {
  color: #475569;
  background: #fff;
  border-color: #cbd5e1;
}

.audit-reset-button:hover,
.audit-reset-button:focus {
  color: #334155;
  background: #f8fafc;
  border-color: #94a3b8;
}

.rule-segment {
  max-width: 100%;
}

.rule-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.rule-search {
  flex: 1 1 260px;
  min-width: 220px;
}

.status-select {
  width: 130px;
  flex: none;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.audit-filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
  overflow: hidden;
}

.audit-filter-bar :deep(.el-select) {
  flex: 0 1 150px;
  min-width: 0;
}

.audit-filter-bar :deep(.el-input) {
  flex: 0 1 150px;
  width: auto;
  min-width: 0;
}

.audit-filter-bar :deep(.audit-time-range) {
  flex: 1 1 380px;
  width: auto;
  min-width: 340px;
  max-width: 100%;
}

.audit-action-tag {
  font-weight: 600;
}

.audit-action-tag--test {
  --el-tag-bg-color: #e0f2fe;
  --el-tag-border-color: #bae6fd;
  --el-tag-text-color: #0284c7;
  --el-tag-hover-color: #0284c7;
}

.audit-action-tag--preference,
.audit-action-tag--update {
  --el-tag-bg-color: #eef2ff;
  --el-tag-border-color: #c7d2fe;
  --el-tag-text-color: #4f46e5;
  --el-tag-hover-color: #4f46e5;
}

.audit-action-tag--create,
.audit-action-tag--enable,
.audit-action-tag--import {
  --el-tag-bg-color: #ecfdf5;
  --el-tag-border-color: #bbf7d0;
  --el-tag-text-color: #16a34a;
  --el-tag-hover-color: #16a34a;
}

.audit-action-tag--disable,
.audit-action-tag--rollback,
.audit-action-tag--export {
  --el-tag-bg-color: #fff7ed;
  --el-tag-border-color: #fed7aa;
  --el-tag-text-color: #ea580c;
  --el-tag-hover-color: #ea580c;
}

.audit-action-tag--delete {
  --el-tag-bg-color: #fef2f2;
  --el-tag-border-color: #fecaca;
  --el-tag-text-color: #dc2626;
  --el-tag-hover-color: #dc2626;
}

.audit-action-tag--init,
.audit-action-tag--default {
  --el-tag-bg-color: #f1f5f9;
  --el-tag-border-color: #cbd5e1;
  --el-tag-text-color: #475569;
  --el-tag-hover-color: #475569;
}

.audit-change-text {
  display: -webkit-box;
  max-height: 44px;
  overflow: hidden;
  color: #334155;
  line-height: 22px;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

:global(.audit-change-tooltip) {
  max-width: 420px;
  padding: 10px 12px;
  color: #1e293b;
  font-size: 13px;
  line-height: 1.65;
  white-space: normal;
  word-break: break-word;
  background: #ffffff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14);
}

:global(.audit-change-tooltip .el-popper__arrow::before) {
  border-color: #bfdbfe;
  background: #ffffff;
}

.side-panel {
  margin-bottom: 16px;
}

.side-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #0f172a;
  font-weight: 700;
}

.side-title .el-icon {
  color: #2563eb;
  font-size: 18px;
}

.palette-row {
  flex-wrap: wrap;
  row-gap: 12px;
}

.palette-item {
  position: relative;
  width: 42px;
  height: 42px;
}

.palette-row :deep(.el-color-picker__trigger) {
  border-radius: 8px;
}

.palette-remove {
  position: absolute;
  top: -7px;
  right: -7px;
  z-index: 2;
  width: 20px;
  height: 20px;
  min-height: 20px;
  padding: 0;
  color: #64748b;
  background: #fff;
  border-color: #cbd5e1;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.12);
}

.palette-remove:hover,
.palette-remove:focus {
  color: #dc2626;
  border-color: #fecaca;
  background: #fff5f5;
}

.switch-line {
  flex-wrap: wrap;
}

.rule-name {
  font-weight: 600;
  color: #0f172a;
}

.chart-type-text {
  color: #334155;
  font-weight: 500;
}

.scenario-tag {
  font-weight: 500;
}

.scenario-tag--time-series {
  --el-tag-bg-color: #e0f2fe;
  --el-tag-border-color: #bae6fd;
  --el-tag-text-color: #0284c7;
  --el-tag-hover-color: #0284c7;
}

.scenario-tag--group-compare {
  --el-tag-bg-color: #eef2ff;
  --el-tag-border-color: #c7d2fe;
  --el-tag-text-color: #4f46e5;
  --el-tag-hover-color: #4f46e5;
}

.scenario-tag--ratio {
  --el-tag-bg-color: #fff7ed;
  --el-tag-border-color: #fed7aa;
  --el-tag-text-color: #ea580c;
  --el-tag-hover-color: #ea580c;
}

.scenario-tag--detail {
  --el-tag-bg-color: #ecfdf5;
  --el-tag-border-color: #bbf7d0;
  --el-tag-text-color: #16a34a;
  --el-tag-hover-color: #16a34a;
}

.scenario-tag--custom {
  --el-tag-bg-color: #fdf2f8;
  --el-tag-border-color: #fbcfe8;
  --el-tag-text-color: #db2777;
  --el-tag-hover-color: #db2777;
}

.scenario-tag--default {
  --el-tag-bg-color: #f1f5f9;
  --el-tag-border-color: #cbd5e1;
  --el-tag-text-color: #475569;
  --el-tag-hover-color: #475569;
}

.mono {
  font-family: Consolas, Monaco, monospace;
}

.inline-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  width: 100%;
}

.structured-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 12px;
  width: 100%;
}

.structured-span {
  grid-column: 1 / -1;
}

.structured-grid :deep(.el-form-item) {
  min-width: 0;
}

.prediction-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}

.dynamic-render-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}

.voice-config-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}

.full-width {
  width: 100%;
}

.prediction-switches {
  flex-wrap: wrap;
}

.prediction-legend-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 10px;
}

.prediction-legend-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
}

.prediction-legend-row :deep(.el-checkbox) {
  margin-right: 0;
}

.range-row {
  display: grid;
  grid-template-columns: minmax(86px, 1fr) auto minmax(86px, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
}

.range-row :deep(.el-input-number) {
  width: 100%;
  min-width: 86px;
}

.range-row :deep(.el-input-number .el-input__wrapper) {
  padding-left: 8px;
  padding-right: 28px;
}

.range-row span {
  color: #64748b;
  font-size: 12px;
}

.test-summary {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 12px 0 10px;
  color: #334155;
  font-size: 13px;
}

.sample-quick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.sample-quick-row span {
  flex: 0 0 100%;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.sample-summary-card {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.sample-summary-card span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.sample-summary-card strong {
  display: block;
  margin-top: 4px;
  color: #0f172a;
  font-size: 14px;
}

.sample-dialog-layout {
  display: grid;
  gap: 14px;
}

.sample-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.sample-dialog-head h4 {
  margin: 0;
  color: #0f172a;
  font-size: 15px;
}

.sample-dialog-head p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.sample-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
}

.sample-field-list {
  display: grid;
  gap: 8px;
}

.sample-field-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px 28px;
  gap: 8px;
  align-items: center;
}

.sample-field-row--dialog {
  grid-template-columns: minmax(180px, 1fr) 160px 34px;
}

.sample-field-row :deep(.el-button) {
  width: 28px;
  height: 28px;
  min-height: 28px;
}

.sample-field-row--dialog :deep(.el-button) {
  width: 34px;
  height: 34px;
  min-height: 34px;
}

.sample-row-table {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.sample-row-table :deep(.el-table__body-wrapper) {
  overflow-x: auto;
}

.sample-row-table--dialog {
  overflow: hidden;
}

.sample-json-advanced {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.sample-json-advanced summary {
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.sample-json-advanced :deep(.el-textarea) {
  margin-top: 10px;
}

.chart-preview {
  width: 100%;
  height: 326px;
  margin-top: 12px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.chart-preview.chart-preview-animating {
  animation: previewChartReplay 0.28s ease-out;
}

@keyframes previewChartReplay {
  0% {
    opacity: 0.72;
  }
  100% {
    opacity: 1;
  }
}

.table-preview {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  color: #64748b;
  text-align: center;
  word-break: break-word;
}

.audit-panel {
  display: flex;
  flex-direction: column;
  height: 661px;
  margin-bottom: 0;
}

.audit-panel :deep(.el-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.audit-panel :deep(.el-table) {
  flex: 1;
  min-height: 0;
}

.audit-log-table :deep(.el-table__body-wrapper) {
  overflow-x: auto;
  overflow-y: auto;
}

.audit-log-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.import-alert {
  margin-bottom: 12px;
}

.import-upload-panel {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  margin-bottom: 14px;
}

.import-upload-copy {
  padding: 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.import-upload-copy h4 {
  margin: 0 0 8px;
  color: #0f172a;
  font-size: 15px;
}

.import-upload-copy p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.import-upload :deep(.el-upload),
.import-upload :deep(.el-upload-dragger) {
  width: 100%;
  height: 100%;
}

.import-upload :deep(.el-upload-dragger) {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  min-height: 132px;
  padding: 18px;
  border-radius: 8px;
}

.import-upload-icon {
  flex: none;
  color: #2563eb;
  font-size: 30px;
}

.import-upload-text {
  display: grid;
  gap: 5px;
  min-width: 0;
  text-align: left;
}

.import-upload-text strong {
  overflow: hidden;
  color: #0f172a;
  font-size: 14px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.import-upload-text span {
  color: #64748b;
  font-size: 12px;
}

.import-advanced {
  margin-bottom: 14px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.import-advanced summary {
  color: #334155;
  font-weight: 600;
  cursor: pointer;
}

.import-advanced :deep(.el-textarea) {
  margin-top: 12px;
}

.import-preview {
  display: grid;
  gap: 10px;
}

.import-preview-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.import-preview-stat {
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.import-preview-stat span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.import-preview-stat strong {
  display: block;
  margin-top: 4px;
  color: #0f172a;
  font-size: 22px;
  line-height: 1;
}

.stat-create {
  border-color: #bbf7d0;
  background: #ecfdf5;
}

.stat-update {
  border-color: #fed7aa;
  background: #fff7ed;
}

.stat-skip {
  border-color: #cbd5e1;
  background: #f8fafc;
}

.stat-conflict {
  border-color: #fecaca;
  background: #fef2f2;
}

.import-preview-table {
  margin-top: 2px;
}

.version-summary {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.version-summary-main {
  min-width: 0;
  overflow: hidden;
  color: #0f172a;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.version-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.version-summary-code {
  display: inline-block;
  max-width: 190px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  vertical-align: middle;
}

.version-summary-meta :deep(.el-tag) {
  flex: none;
}

@media (max-width: 1280px) {
  .rule-workspace {
    grid-template-columns: 1fr;
  }

  .workspace-side {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }
}

@media (max-width: 900px) {
  .chart-rule-page {
    padding: 14px;
  }

  .chart-rule-hero {
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .panel-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .audit-head-actions {
    justify-content: flex-start;
  }

  .workspace-side {
    grid-template-columns: 1fr;
  }

  .audit-filter-bar :deep(.audit-time-range) {
    flex-basis: 100%;
    min-width: 0;
  }

  .audit-panel {
    height: auto;
    max-height: none;
  }

  .inline-grid {
    grid-template-columns: 1fr;
  }

  .structured-grid {
    grid-template-columns: 1fr;
  }

  .prediction-legend-grid {
    grid-template-columns: 1fr;
  }

  .rule-toolbar {
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .metric-card {
    min-height: 92px;
  }

  .rule-toolbar > * {
    width: 100%;
  }

  .audit-filter-bar > *,
  .audit-filter-bar :deep(.el-select),
  .audit-filter-bar :deep(.el-input),
  .audit-filter-bar :deep(.audit-time-range) {
    flex: 1 1 100%;
    width: 100%;
  }

  .audit-head-actions :deep(.el-button) {
    flex: 1 1 auto;
  }

  .table-footer {
    justify-content: flex-start;
    overflow-x: auto;
  }
}
</style>
