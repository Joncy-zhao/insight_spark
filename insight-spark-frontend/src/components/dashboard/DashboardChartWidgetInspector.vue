<template>
  <el-drawer
    v-model="innerVisible"
    direction="rtl"
    :size="drawerSize"
    :with-header="true"
    :show-close="true"
    :modal="false"
    append-to-body
    destroy-on-close
    class="dcwi-drawer"
    @closed="emit('closed')"
  >
    <template #header>
      <div class="dcwi-header">
        <div class="dcwi-header-main">
          <span class="dcwi-title">图表编辑</span>
          <el-tag v-if="inspectorMode.mode === 'fallback'" size="small" type="info" effect="plain">
            自定义图表
          </el-tag>
        </div>
        <div class="dcwi-header-actions">
          <el-button text size="small" @click="emit('collapse')">收起</el-button>
        </div>
      </div>
    </template>

    <div v-if="gridItem && payload" class="dcwi-body">
      <div v-if="inspectorMode.mode === 'fallback'" class="dcwi-banner">
        当前为自定义图表，仅支持通用样式与布局设置
      </div>

      <div v-if="deformHint" class="dcwi-hint dcwi-hint--warn">
        {{ deformHint }}
        <el-button v-if="showAxisJump" link type="primary" size="small" @click="jumpToAxisPanel">
          调整 X 轴文字旋转
        </el-button>
      </div>

      <div v-if="overlapHint" class="dcwi-hint dcwi-hint--info">
        {{ overlapHint }}
      </div>

      <div class="dcwi-type-row">
        <span class="dcwi-k">图表类型</span>
        <el-select
          v-model="manualTypeOverride"
          size="small"
          class="dcwi-type-select"
          @change="onManualTypeChange"
        >
          <el-option v-for="opt in CHART_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <span class="dcwi-type-meta muted">{{ inspectorMode.label }}</span>
      </div>

      <el-collapse v-model="expandedPanels" class="dcwi-collapse">
        <!-- 面板 1：通用基础设置 -->
        <el-collapse-item name="basic" title="通用基础设置">
          <div class="dcwi-section">
            <p class="dcwi-tip muted">
              调整卡片标题、图例、悬浮提示等通用外观；修改后画布实时预览，点击「应用并关闭」后写入布局。
            </p>

            <div class="dcwi-subsection">
              <div class="dcwi-subsection-title">标题样式</div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  卡片标题
                  <el-tooltip content="显示在看板卡片顶栏；留空则使用图表默认名称" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-input
                  :model-value="gridItem.title || ''"
                  size="small"
                  placeholder="留空使用默认标题"
                  class="dcwi-grow"
                  @update:model-value="patch({ title: $event || null })"
                />
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  标题字号
                  <el-tooltip content="卡片顶栏标题的文字大小，右侧色块可改标题颜色" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-input-number
                  :model-value="chartStyle.titleFontSize"
                  :min="10"
                  :max="32"
                  size="small"
                  controls-position="right"
                  class="dcwi-num-sm"
                  @update:model-value="patchChartStyle({ titleFontSize: $event })"
                />
                <el-color-picker
                  :model-value="chartStyle.titleColor"
                  size="small"
                  @change="(c) => patchChartStyle({ titleColor: c })"
                />
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  标题粗细
                  <el-tooltip content="控制标题字重，数值越大越粗" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-select
                  :model-value="chartStyle.titleFontWeight"
                  size="small"
                  class="dcwi-select-weight"
                  @update:model-value="patchChartStyle({ titleFontWeight: $event })"
                >
                  <el-option label="常规 · 400" :value="400" />
                  <el-option label="中等 · 500" :value="500" />
                  <el-option label="半粗 · 600" :value="600" />
                  <el-option label="加粗 · 700" :value="700" />
                </el-select>
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  标题位置
                  <el-tooltip content="标题文字在卡片顶栏的对齐方式" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-radio-group
                  :model-value="chartStyle.titleAlign"
                  size="small"
                  @update:model-value="patchChartStyle({ titleAlign: $event })"
                >
                  <el-radio-button label="left">左侧</el-radio-button>
                  <el-radio-button label="center">居中</el-radio-button>
                  <el-radio-button label="right">右侧</el-radio-button>
                </el-radio-group>
              </div>
            </div>

            <div class="dcwi-subsection">
              <div class="dcwi-subsection-title">图例与提示</div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  图例
                  <el-tooltip content="标识各系列颜色与名称的说明块，多系列图表建议开启" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.legendShow"
                  @update:model-value="patchChartStyle({ legendShow: $event })"
                />
                <el-select
                  v-if="chartStyle.legendShow"
                  :model-value="chartStyle.legendPosition"
                  size="small"
                  class="dcwi-select-sm"
                  @update:model-value="patchChartStyle({ legendPosition: $event })"
                >
                  <el-option label="顶部" value="top" />
                  <el-option label="底部" value="bottom" />
                  <el-option label="左侧" value="left" />
                  <el-option label="右侧" value="right" />
                </el-select>
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  悬浮提示
                  <el-tooltip content="鼠标移到数据点上弹出的数值说明框，关闭后悬停不再显示" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.tooltipShow"
                  @update:model-value="patchChartStyle({ tooltipShow: $event })"
                />
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  数据标签
                  <el-tooltip content="在柱顶、折线点或扇区旁直接显示数值，适合强调具体数字" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.dataLabelShow"
                  @update:model-value="patchChartStyle({ dataLabelShow: $event })"
                />
              </div>
            </div>

            <div v-if="showAxisPanel" class="dcwi-subsection">
              <div class="dcwi-subsection-title">坐标轴</div>
              <p class="dcwi-tip muted">适用于柱状图、折线图等带 X/Y 轴的图表；标签过长时可旋转 X 轴文字。</p>
              <div class="dcwi-row dcwi-row--slider">
                <span class="dcwi-k dcwi-k--with-help">
                  X 轴文字旋转
                  <el-tooltip content="旋转横轴分类标签角度，避免文字重叠；常用 0°、45°、90°" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-slider
                  :model-value="chartStyle.axisXRotate"
                  :min="-90"
                  :max="90"
                  :step="15"
                  class="dcwi-slider"
                  @update:model-value="patchChartStyle({ axisXRotate: $event })"
                />
                <span class="dcwi-val">{{ chartStyle.axisXRotate }}°</span>
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  Y 轴刻度
                  <el-tooltip content="控制纵轴刻度数字是否显示，关闭后不再显示 Y 轴数值" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.axisYShow"
                  @update:model-value="patchChartStyle({ axisYShow: $event })"
                />
                <span class="dcwi-k dcwi-k--with-help">
                  网格线
                  <el-tooltip content="图表背景中的横纵参考线，关闭后背景更干净" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.gridLineShow"
                  @update:model-value="patchChartStyle({ gridLineShow: $event })"
                />
              </div>
            </div>
          </div>
        </el-collapse-item>

        <!-- 面板 2：图表专属样式 -->
        <el-collapse-item v-if="hasSpecificStylePanel" name="specific" title="图表专属样式">
          <div v-if="chartIsBar(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">柱状图专用：控制所有柱子的整体外观；若某根柱已在「分项配色」单独设色，则以分项为准。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                柱颜色
                <el-tooltip content="所有柱子的统一默认色；未单独配色的柱子都用这个颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker :model-value="barColorModel" show-alpha @change="onBarColorChange" />
              <el-button text type="primary" size="small" @click="patch({ barColor: null, barMaxWidth: null })">
                恢复默认
              </el-button>
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                柱宽度上限
                <el-tooltip content="限制单根柱子的最大宽度；分类少或卡片被拉宽时，防止柱子过粗" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="barMaxWidthModel"
                :min="8"
                :max="72"
                :step="2"
                class="dcwi-slider"
                @update:model-value="patch({ barMaxWidth: $event })"
              />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                柱圆角
                <el-tooltip content="柱子顶部的圆角大小；0 为直角，越大越圆润" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.barRadius"
                :min="0"
                :max="16"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ barRadius: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsLine(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">折线图专用：控制线条颜色、粗细与拐点样式；分项配色可覆盖单条折线颜色。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                线条颜色
                <el-tooltip content="所有折线的默认颜色；未单独配色的系列使用此色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker :model-value="barColorModel" show-alpha @change="onBarColorChange" />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                线条粗细
                <el-tooltip content="折线宽度，数值越大线条越粗" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.lineWidth"
                :min="1"
                :max="6"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ lineWidth: $event })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                平滑曲线
                <el-tooltip content="开启后折线变为圆滑曲线，关闭则为折角连接" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-switch
                :model-value="chartStyle.lineSmooth"
                @update:model-value="patchChartStyle({ lineSmooth: $event })"
              />
              <span class="dcwi-k dcwi-k--with-help">
                拐点大小
                <el-tooltip content="数据点标记圆点的大小，0 时几乎不可见" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.lineSymbolSize"
                :min="2"
                :max="16"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ lineSymbolSize: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsPie(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">饼图/环图专用：调整环形比例与扇区间距；各扇区颜色可在「分项配色」中单独设置。</p>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                环内径 %
                <el-tooltip content="内圈空白占比，0 为实心饼图，越大越接近环图" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.pieInnerRadius"
                :min="0"
                :max="80"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ pieInnerRadius: $event })"
              />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                扇区间距
                <el-tooltip content="各扇区之间的缝隙角度，越大分区越明显" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.piePadAngle"
                :min="0"
                :max="20"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ piePadAngle: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsTable(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">表格专用：调整表头/正文字号与斑马纹；修改后画布实时预览。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                表头字号
                <el-tooltip content="表格列标题的文字大小" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.tableHeaderFontSize"
                :min="10"
                :max="24"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ tableHeaderFontSize: $event })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                正文字号
                <el-tooltip content="表格单元格内容的文字大小" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.tableBodyFontSize"
                :min="10"
                :max="20"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ tableBodyFontSize: $event })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                斑马纹
                <el-tooltip content="奇偶行交替背景色，便于横向阅读" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-switch
                :model-value="chartStyle.tableStripe"
                @update:model-value="patchChartStyle({ tableStripe: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsScatter(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">散点图专用：控制散点颜色、大小与透明度。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                散点颜色
                <el-tooltip content="所有散点的默认颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker :model-value="barColorModel" show-alpha @change="onBarColorChange" />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                散点大小
                <el-tooltip content="散点圆点直径，数值越大点越大" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.scatterSymbolSize"
                :min="4"
                :max="24"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ scatterSymbolSize: $event })"
              />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                散点透明度
                <el-tooltip content="0 为全透明，1 为不透明" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="Math.round(chartStyle.scatterOpacity * 100)"
                :min="20"
                :max="100"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ scatterOpacity: $event / 100 })"
              />
            </div>
          </div>
          <div v-else-if="chartIsRadar(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">雷达图专用：控制填充区域、边线与拐点样式。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                线条颜色
                <el-tooltip content="雷达边线与拐点颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker :model-value="barColorModel" show-alpha @change="onBarColorChange" />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                区域透明度
                <el-tooltip content="雷达内部填充区域的透明度" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="Math.round(chartStyle.radarAreaOpacity * 100)"
                :min="0"
                :max="80"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ radarAreaOpacity: $event / 100 })"
              />
            </div>
            <div class="dcwi-row dcwi-row--slider">
              <span class="dcwi-k dcwi-k--with-help">
                线条粗细
                <el-tooltip content="雷达多边形边线宽度" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-slider
                :model-value="chartStyle.radarLineWidth"
                :min="1"
                :max="6"
                class="dcwi-slider"
                @update:model-value="patchChartStyle({ radarLineWidth: $event })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                拐点大小
                <el-tooltip content="各维度顶点圆点大小" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.radarSymbolSize"
                :min="2"
                :max="12"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ radarSymbolSize: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsMetric(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">指标卡专用：突出显示核心数值与指标名称。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                数值字号
                <el-tooltip content="指标主数值的字号" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.metricValueFontSize"
                :min="20"
                :max="56"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ metricValueFontSize: $event })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                数值颜色
                <el-tooltip content="指标主数值的颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.metricValueColor || '#0f172a'"
                @change="(c) => patchChartStyle({ metricValueColor: c })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                名称字号
                <el-tooltip content="指标名称（副标题）的字号" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-input-number
                :model-value="chartStyle.metricLabelFontSize"
                :min="10"
                :max="20"
                size="small"
                controls-position="right"
                @update:model-value="patchChartStyle({ metricLabelFontSize: $event })"
              />
            </div>
          </div>
          <div v-else-if="chartIsMap(inspectorMode.chartType)" class="dcwi-section">
            <p class="dcwi-tip muted">地图专用：调整区域底色、边界与悬停高亮色（需快照含地图配置）。</p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                区域底色
                <el-tooltip content="地图各区域的默认填充色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.mapAreaColor || '#e8f4fc'"
                @change="(c) => patchChartStyle({ mapAreaColor: c })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                边界颜色
                <el-tooltip content="省界/区域边界线颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.mapBorderColor || '#b0c4de'"
                @change="(c) => patchChartStyle({ mapBorderColor: c })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                悬停高亮
                <el-tooltip content="鼠标悬停某区域时的高亮色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.mapEmphasisColor || '#5470c6'"
                @change="(c) => patchChartStyle({ mapEmphasisColor: c })"
              />
            </div>
          </div>
        </el-collapse-item>

        <!-- 面板 3：分项配色（标准 + 有分项） -->
        <el-collapse-item
          v-if="isStandard && supportsSeriesColors && seriesPoints.length"
          name="series"
        >
          <template #title>
            <span>分项配色</span>
            <span class="dcwi-collapse-meta muted">共 {{ seriesPoints.length }} 项</span>
          </template>
          <div class="dcwi-section">
            <p class="dcwi-tip muted">
              为每个分类（柱子、扇区、折线等）单独设色；左右滑动切换分类，下方修改当前项颜色。
            </p>
            <el-input
              v-model="seriesSearch"
              size="small"
              clearable
              placeholder="搜索分类名称"
              class="dcwi-search"
            />
            <div class="dcwi-batch-row">
              <el-button size="small" @click="clearAllSeriesColors">清除全部</el-button>
              <el-button size="small" @click="resetSeriesToDefault">恢复默认配色</el-button>
            </div>

            <div v-if="filteredSeriesEntries.length" class="dcwi-series-nav">
              <button
                type="button"
                class="dcwi-series-nav-arrow"
                :disabled="activeSeriesNavIndex <= 0"
                aria-label="上一项"
                @click="goPrevSeries"
              >
                ‹
              </button>
              <div ref="seriesNavStripRef" class="dcwi-series-nav-strip">
                <button
                  v-for="(entry, navIdx) in filteredSeriesEntries"
                  :key="entry.idx"
                  type="button"
                  :class="[
                    'dcwi-series-tab',
                    {
                      active: navIdx === activeSeriesNavIndex,
                      'has-custom': hasCustomSeriesColor(entry.idx)
                    }
                  ]"
                  @click="selectSeriesNav(navIdx)"
                >
                  <span
                    class="dcwi-series-tab-dot"
                    :style="{ background: seriesColorModel(entry.idx) }"
                    aria-hidden="true"
                  />
                  <span class="dcwi-series-tab-name" :title="entry.pt.name">{{ entry.pt.name }}</span>
                  <span
                    v-if="hasCustomSeriesColor(entry.idx)"
                    class="dcwi-series-tab-clear"
                    title="清除此项配色"
                    @click.stop="onSeriesColorChange(entry.idx, null)"
                  >×</span>
                </button>
              </div>
              <button
                type="button"
                class="dcwi-series-nav-arrow"
                :disabled="activeSeriesNavIndex >= filteredSeriesEntries.length - 1"
                aria-label="下一项"
                @click="goNextSeries"
              >
                ›
              </button>
            </div>

            <div v-if="activeSeriesEntry" class="dcwi-series-detail">
              <div class="dcwi-series-detail-head">
                <div class="dcwi-series-detail-meta">
                  <span class="dcwi-series-detail-name">{{ activeSeriesEntry.pt.name }}</span>
                  <span class="dcwi-series-detail-val muted">{{ formatValue(activeSeriesEntry.pt.value) }}</span>
                </div>
                <span class="dcwi-series-detail-pos muted">
                  {{ activeSeriesNavIndex + 1 }} / {{ filteredSeriesEntries.length }}
                </span>
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  分项颜色
                  <el-tooltip content="仅影响当前选中分类；清除后恢复为图表专属样式中的默认色" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-color-picker
                  :model-value="seriesColorModel(activeSeriesEntry.idx)"
                  show-alpha
                  @change="(c) => onSeriesColorChange(activeSeriesEntry.idx, c)"
                />
                <span class="dcwi-series-color-hex muted">{{ seriesColorModel(activeSeriesEntry.idx) }}</span>
              </div>
            </div>
            <p v-else class="dcwi-tip muted">无匹配分类，请调整搜索关键词。</p>
          </div>
        </el-collapse-item>

        <!-- 面板 3b：全局配色（自定义图表兜底） -->
        <el-collapse-item v-if="!isStandard" name="globalColor" title="全局配色">
          <div class="dcwi-section">
            <p class="dcwi-tip muted">
              自定义图表无法按系列分项设色，此处统一调整主色、辅助色与卡片背景。
            </p>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                主色
                <el-tooltip content="图表主要数据系列使用的颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.primaryColor || '#5470c6'"
                @change="(c) => patchChartStyle({ primaryColor: c })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                辅助色
                <el-tooltip content="第二系列或对比数据使用的颜色" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.secondaryColor || '#91cc75'"
                @change="(c) => patchChartStyle({ secondaryColor: c })"
              />
            </div>
            <div class="dcwi-row">
              <span class="dcwi-k dcwi-k--with-help">
                背景色
                <el-tooltip content="图表绘制区域背景，留空或透明则使用卡片默认白底" placement="top">
                  <span class="dcwi-help">?</span>
                </el-tooltip>
              </span>
              <el-color-picker
                :model-value="chartStyle.backgroundColor || ''"
                show-alpha
                @change="(c) => patchChartStyle({ backgroundColor: c })"
              />
            </div>
          </div>
        </el-collapse-item>

        <!-- 面板 4：卡片布局 -->
        <el-collapse-item name="layout" title="卡片布局">
          <div class="dcwi-section">
            <p class="dcwi-tip muted">
              控制图表卡片在看板上的大小与位置。可直接拖拽缩放，也可在此输入精确数值。
            </p>

            <div class="dcwi-size-preview">
              <div class="dcwi-size-preview-main">
                <span class="dcwi-size-preview-val">{{ gridItem.w }} × {{ gridItem.h }}</span>
                <span class="dcwi-size-preview-unit">列 × 行</span>
              </div>
              <span class="dcwi-size-preview-pos muted">位置 x={{ gridItem.x }}, y={{ gridItem.y }}</span>
            </div>

            <div class="dcwi-subsection">
              <div class="dcwi-subsection-title">调整占位</div>
              <div class="dcwi-field-grid">
                <div class="dcwi-field">
                  <span class="dcwi-field-label">
                    宽度（列）
                    <el-tooltip content="卡片占用的横向栅格列数，看板共 24 列" placement="top">
                      <span class="dcwi-help">?</span>
                    </el-tooltip>
                  </span>
                  <el-input-number
                    :model-value="gridItem.w"
                    :min="layoutConstraints.minW"
                    :max="layoutConstraints.maxW"
                    size="small"
                    controls-position="right"
                    class="dcwi-field-input"
                    @update:model-value="patch({ w: $event })"
                  />
                </div>
                <div class="dcwi-field">
                  <span class="dcwi-field-label">
                    高度（行）
                    <el-tooltip content="卡片占用的纵向行数，行越高图表区域越大" placement="top">
                      <span class="dcwi-help">?</span>
                    </el-tooltip>
                  </span>
                  <el-input-number
                    :model-value="gridItem.h"
                    :min="layoutConstraints.minH"
                    :max="layoutConstraints.maxH"
                    size="small"
                    controls-position="right"
                    class="dcwi-field-input"
                    @update:model-value="patch({ h: $event })"
                  />
                </div>
              </div>
            </div>

            <el-collapse class="dcwi-nested-collapse">
              <el-collapse-item name="layout-more" title="更多布局选项">
                <div class="dcwi-section dcwi-section--nested">
                  <p class="dcwi-tip muted">仅在需要限制缩放范围或固定拖拽行为时使用，一般保持默认即可。</p>

                  <div class="dcwi-subsection">
                    <div class="dcwi-subsection-title">缩放范围</div>
                    <div class="dcwi-field-grid">
                      <div class="dcwi-field">
                        <span class="dcwi-field-label">
                          最小宽
                          <el-tooltip content="拖拽缩小时，宽度不能小于此值" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-input-number
                          :model-value="layoutConstraints.minW"
                          :min="2"
                          :max="24"
                          size="small"
                          controls-position="right"
                          class="dcwi-field-input"
                          @update:model-value="patchLayoutConstraints({ minW: $event })"
                        />
                      </div>
                      <div class="dcwi-field">
                        <span class="dcwi-field-label">
                          最小高
                          <el-tooltip content="拖拽缩小时，高度不能小于此值" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-input-number
                          :model-value="layoutConstraints.minH"
                          :min="2"
                          :max="48"
                          size="small"
                          controls-position="right"
                          class="dcwi-field-input"
                          @update:model-value="patchLayoutConstraints({ minH: $event })"
                        />
                      </div>
                      <div class="dcwi-field">
                        <span class="dcwi-field-label">
                          最大宽
                          <el-tooltip content="拖拽放大时，宽度不能超过此值" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-input-number
                          :model-value="layoutConstraints.maxW"
                          :min="4"
                          :max="24"
                          size="small"
                          controls-position="right"
                          class="dcwi-field-input"
                          @update:model-value="patchLayoutConstraints({ maxW: $event })"
                        />
                      </div>
                      <div class="dcwi-field">
                        <span class="dcwi-field-label">
                          最大高
                          <el-tooltip content="拖拽放大时，高度不能超过此值" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-input-number
                          :model-value="layoutConstraints.maxH"
                          :min="2"
                          :max="48"
                          size="small"
                          controls-position="right"
                          class="dcwi-field-input"
                          @update:model-value="patchLayoutConstraints({ maxH: $event })"
                        />
                      </div>
                    </div>
                  </div>

                  <div class="dcwi-subsection">
                    <div class="dcwi-subsection-title">拖拽行为</div>
                    <div class="dcwi-switch-row">
                      <div class="dcwi-switch-item">
                        <span class="dcwi-field-label">
                          吸附网格
                          <el-tooltip content="拖动卡片时自动对齐栅格，减少重叠错位" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-switch
                          :model-value="layoutConstraints.gridSnap"
                          @update:model-value="patchLayoutConstraints({ gridSnap: $event })"
                        />
                      </div>
                      <div class="dcwi-switch-item">
                        <span class="dcwi-field-label">格子大小</span>
                        <el-input-number
                          :model-value="layoutConstraints.gridCellSize"
                          :min="1"
                          :max="6"
                          size="small"
                          controls-position="right"
                          class="dcwi-field-input dcwi-field-input--sm"
                          :disabled="!layoutConstraints.gridSnap"
                          @update:model-value="patchLayoutConstraints({ gridCellSize: $event })"
                        />
                      </div>
                    </div>
                    <div class="dcwi-switch-row">
                      <div class="dcwi-switch-item">
                        <span class="dcwi-field-label">
                          锁定宽高
                          <el-tooltip content="开启后只能移动位置，不能缩放大小" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-switch
                          :model-value="layoutConstraints.lockSize"
                          @update:model-value="patchLayoutConstraints({ lockSize: $event })"
                        />
                      </div>
                      <div class="dcwi-switch-item">
                        <span class="dcwi-field-label">
                          锁定位置
                          <el-tooltip content="开启后固定在当前位置，不能拖动" placement="top">
                            <span class="dcwi-help">?</span>
                          </el-tooltip>
                        </span>
                        <el-switch
                          :model-value="layoutConstraints.lockPosition"
                          @update:model-value="patchLayoutConstraints({ lockPosition: $event })"
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-collapse-item>

        <!-- 面板 5：高级交互 -->
        <el-collapse-item name="advanced" title="高级交互">
          <div class="dcwi-section">
            <p class="dcwi-tip muted">
              微调图表卡片容器的外观（圆角、阴影），不影响图表内部的数据绘制。
            </p>
            <div class="dcwi-subsection">
              <div class="dcwi-subsection-title">卡片外观</div>
              <div class="dcwi-row dcwi-row--slider">
                <span class="dcwi-k dcwi-k--with-help">
                  卡片圆角
                  <el-tooltip content="看板卡片四角的圆角半径，0 为直角" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-slider
                  :model-value="chartStyle.cardBorderRadius"
                  :min="0"
                  :max="24"
                  class="dcwi-slider"
                  @update:model-value="patchChartStyle({ cardBorderRadius: $event })"
                />
              </div>
              <div class="dcwi-row">
                <span class="dcwi-k dcwi-k--with-help">
                  卡片阴影
                  <el-tooltip content="开启后卡片带有轻微投影，增强层次感" placement="top">
                    <span class="dcwi-help">?</span>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="chartStyle.cardShadow"
                  @update:model-value="patchChartStyle({ cardShadow: $event })"
                />
              </div>
            </div>
            <p class="dcwi-tip muted">图表联动、下钻与样式模板将在后续版本开放。</p>
          </div>
        </el-collapse-item>
      </el-collapse>

      <el-collapse class="dcwi-meta-collapse">
        <el-collapse-item title="数据源（只读）" name="meta">
          <p class="dcwi-tip muted dcwi-meta-tip">
            只读信息，展示当前图表绑定的数据来源，不可在此修改。
          </p>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="图表类型">{{ rawChartType }}</el-descriptions-item>
            <el-descriptions-item label="数据表">{{ tableName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="结果行数">{{ dataRows }}</el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
      </el-collapse>
    </div>
    <el-empty v-else description="无图表载荷" />

    <template #footer>
      <div class="dcwi-footer">
        <el-button type="primary" @click="emit('save')">应用并关闭</el-button>
        <el-button @click="emit('reset-style')">重置样式</el-button>
        <el-button @click="emit('reset-layout')">重置布局</el-button>
        <el-button @click="emit('cancel')">取消</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { normalizedChartDataPoints } from '../../utils/chartOptionFromSnapshot.js'
import {
  CHART_TYPE_OPTIONS,
  resolveChartInspectorMode,
  chartSupportsSeriesColors,
  chartSupportsAxisSettings,
  chartIsBar,
  chartIsLine,
  chartIsPie,
  chartIsTable,
  chartIsMetric,
  chartIsRadar,
  chartIsScatter,
  chartIsMap,
  chartHasSpecificStylePanel
} from '../../utils/chartInspectorTypes.js'
import { mergeChartStyle, mergeLayoutConstraints, resolveSeriesItemDisplayColor } from '../../utils/chartUiConfig.js'

function seriesUiFromGridItem() {
  return {
    barColor: props.gridItem?.barColor,
    seriesItemStyles: props.gridItem?.seriesItemStyles
  }
}

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  gridItem: { type: Object, default: null },
  payload: { type: Object, default: null },
  rawChartType: { type: String, default: '' },
  tableName: { type: String, default: '' },
  dataRows: { type: Number, default: 0 },
  overlapHint: { type: String, default: '' },
  paramError: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:modelValue',
  'patch-grid-item',
  'collapse',
  'save',
  'reset-style',
  'reset-layout',
  'cancel',
  'closed'
])

const drawerSize = '440px'
const manualTypeOverride = ref('auto')
const seriesSearch = ref('')
const activeSeriesNavIndex = ref(0)
const seriesNavStripRef = ref(null)

const innerVisible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const inspectorMode = computed(() =>
  resolveChartInspectorMode(props.rawChartType, manualTypeOverride.value)
)

const isStandard = computed(() => inspectorMode.value.mode === 'standard')

const chartStyle = computed(() => mergeChartStyle(props.gridItem?.chartStyle))

const layoutConstraints = computed(() => mergeLayoutConstraints(props.gridItem?.layoutConstraints))

const supportsSeriesColors = computed(() =>
  chartSupportsSeriesColors(inspectorMode.value.chartType)
)

const showAxisPanel = computed(() =>
  chartSupportsAxisSettings(inspectorMode.value.chartType)
)

const hasSpecificStylePanel = computed(
  () => isStandard.value && chartHasSpecificStylePanel(inspectorMode.value.chartType)
)

function defaultExpandedPanels() {
  const panels = ['basic']
  if (isStandard.value) {
    if (chartHasSpecificStylePanel(inspectorMode.value.chartType)) panels.push('specific')
  } else {
    panels.push('globalColor')
  }
  return panels
}

const expandedPanels = ref(defaultExpandedPanels())

watch(
  () => [inspectorMode.value.mode, inspectorMode.value.chartType],
  () => {
    expandedPanels.value = defaultExpandedPanels()
  }
)

const seriesPoints = computed(() => {
  if (!props.payload) return []
  return normalizedChartDataPoints(props.payload)
})

const filteredSeriesEntries = computed(() => {
  const kw = String(seriesSearch.value || '').trim().toLowerCase()
  return seriesPoints.value
    .map((pt, idx) => ({ idx, pt }))
    .filter(({ pt }) => !kw || String(pt.name || '').toLowerCase().includes(kw))
})

const activeSeriesEntry = computed(
  () => filteredSeriesEntries.value[activeSeriesNavIndex.value] || null
)

watch(filteredSeriesEntries, (list) => {
  if (!list.length) {
    activeSeriesNavIndex.value = 0
    return
  }
  if (activeSeriesNavIndex.value >= list.length) {
    activeSeriesNavIndex.value = list.length - 1
  }
})

watch(activeSeriesNavIndex, () => {
  nextTick(() => scrollActiveSeriesTabIntoView())
})

const barColorModel = computed(() => {
  const c = String(props.gridItem?.barColor || '').trim()
  return c || '#5470c6'
})

const barMaxWidthModel = computed(() => {
  const w = Number(props.gridItem?.barMaxWidth)
  return Number.isFinite(w) && w >= 8 ? w : 32
})

const deformHint = computed(() => {
  if (!props.gridItem) return ''
  const w = Number(props.gridItem.w) || 0
  const pts = seriesPoints.value.length
  if (w < 6 && pts > 8) {
    return '当前图表宽度过小，X 轴文字可能拥挤'
  }
  if (w < 4) return '当前图表宽度过小，建议放大卡片或调整坐标轴'
  return ''
})

const showAxisJump = computed(() => Boolean(deformHint.value) && showAxisPanel.value)

function jumpToAxisPanel() {
  if (!expandedPanels.value.includes('basic')) {
    expandedPanels.value = [...expandedPanels.value, 'basic']
  }
}

function formatValue(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v ?? '')
  return n.toLocaleString()
}

function patch(partial) {
  emit('patch-grid-item', partial)
}

function patchChartStyle(partial) {
  const next = { ...chartStyle.value, ...partial }
  patch({ chartStyle: next })
}

function patchLayoutConstraints(partial) {
  const next = { ...layoutConstraints.value, ...partial }
  patch({ layoutConstraints: next })
}

function onManualTypeChange() {
  // 仅影响面板展示，不写入 layout
}

function onBarColorChange(val) {
  patch({ barColor: val == null || val === '' ? null : val })
}

function hasCustomSeriesColor(idx) {
  const c = props.gridItem?.seriesItemStyles?.[String(idx)]?.color
  return c != null && String(c).trim() !== ''
}

function selectSeriesNav(navIdx) {
  activeSeriesNavIndex.value = navIdx
}

function goPrevSeries() {
  if (activeSeriesNavIndex.value > 0) {
    activeSeriesNavIndex.value -= 1
  }
}

function goNextSeries() {
  if (activeSeriesNavIndex.value < filteredSeriesEntries.value.length - 1) {
    activeSeriesNavIndex.value += 1
  }
}

function scrollActiveSeriesTabIntoView() {
  const strip = seriesNavStripRef.value
  if (!strip) return
  const tab = strip.querySelector('.dcwi-series-tab.active')
  tab?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest', inline: 'center' })
}

function seriesColorModel(idx) {
  return resolveSeriesItemDisplayColor(
    idx,
    seriesUiFromGridItem(),
    inspectorMode.value.chartType
  )
}

function onSeriesColorChange(idx, val) {
  const base =
    props.gridItem?.seriesItemStyles && typeof props.gridItem.seriesItemStyles === 'object'
      ? { ...props.gridItem.seriesItemStyles }
      : {}
  if (val == null || val === '') delete base[String(idx)]
  else base[String(idx)] = { color: String(val).trim() }
  patch({ seriesItemStyles: Object.keys(base).length ? base : null })
}

function clearAllSeriesColors() {
  patch({ seriesItemStyles: null })
}

function resetSeriesToDefault() {
  clearAllSeriesColors()
}
</script>

<style scoped>
.dcwi-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.dcwi-header-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dcwi-title {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}
.dcwi-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 8px;
}
.dcwi-banner {
  padding: 8px 12px;
  font-size: 12px;
  color: #64748b;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  line-height: 1.5;
}
.dcwi-hint {
  padding: 8px 12px;
  font-size: 12px;
  border-radius: 8px;
  line-height: 1.5;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.dcwi-hint--warn {
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
}
.dcwi-hint--info {
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}
.dcwi-type-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.dcwi-type-select { width: 140px; }
.dcwi-type-meta { font-size: 12px; }
.dcwi-collapse :deep(.el-collapse-item__header) {
  font-weight: 600;
  font-size: 13px;
}
.dcwi-collapse-meta {
  margin-left: 8px;
  font-weight: 400;
  font-size: 12px;
}
.dcwi-section { display: flex; flex-direction: column; gap: 10px; }
.dcwi-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dcwi-row--slider { align-items: center; }
.dcwi-k {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
  min-width: 72px;
}
.dcwi-k--with-help {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.dcwi-help {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  cursor: help;
  flex-shrink: 0;
}
.dcwi-grow { flex: 1; min-width: 120px; }
.dcwi-select-sm { width: 100px; }
.dcwi-select-weight { width: 130px; }
.dcwi-num-sm { width: 100px; }
.dcwi-slider { flex: 1; min-width: 120px; }
.dcwi-val { font-size: 12px; color: #64748b; min-width: 36px; }
.dcwi-tip { margin: 0; font-size: 12px; line-height: 1.5; }
.dcwi-size-preview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}
.dcwi-size-preview-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.dcwi-size-preview-val {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
}
.dcwi-size-preview-unit {
  font-size: 12px;
  color: #64748b;
}
.dcwi-size-preview-pos {
  font-size: 11px;
  white-space: nowrap;
}
.dcwi-subsection {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dcwi-subsection-title {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}
.dcwi-field-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 12px;
}
.dcwi-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.dcwi-field-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.3;
}
.dcwi-field-input {
  width: 100%;
}
.dcwi-field-input--sm {
  max-width: 100px;
}
.dcwi-switch-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 12px;
}
.dcwi-switch-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dcwi-section--nested {
  gap: 12px;
}
.dcwi-nested-collapse {
  border: none;
  --el-collapse-header-height: 36px;
}
.dcwi-nested-collapse :deep(.el-collapse-item__header) {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  background: transparent;
  border-bottom: none;
  height: 36px;
  line-height: 36px;
}
.dcwi-nested-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.dcwi-nested-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}
.dcwi-live-size { font-size: 13px; color: #374151; }
.dcwi-search { width: 100%; }
.dcwi-batch-row { display: flex; gap: 8px; flex-wrap: wrap; }
.dcwi-series-nav {
  display: flex;
  align-items: stretch;
  gap: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  overflow: hidden;
}
.dcwi-series-nav-arrow {
  flex-shrink: 0;
  width: 28px;
  border: none;
  background: #f3f4f6;
  color: #64748b;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}
.dcwi-series-nav-arrow:hover:not(:disabled) {
  background: #e5e7eb;
  color: #111827;
}
.dcwi-series-nav-arrow:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.dcwi-series-nav-strip {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
  gap: 0;
  overflow-x: auto;
  scroll-behavior: smooth;
  scrollbar-width: none;
}
.dcwi-series-nav-strip::-webkit-scrollbar {
  display: none;
}
.dcwi-series-tab {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 120px;
  padding: 8px 10px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}
.dcwi-series-tab:hover {
  color: #111827;
  background: rgba(255, 255, 255, 0.6);
}
.dcwi-series-tab.active {
  color: #2563eb;
  border-bottom-color: #2563eb;
  background: #fff;
  font-weight: 600;
}
.dcwi-series-tab-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
  border: 1px solid rgba(0, 0, 0, 0.08);
}
.dcwi-series-tab-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.dcwi-series-tab-clear {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  line-height: 12px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  color: #94a3b8;
}
.dcwi-series-tab-clear:hover {
  background: #fee2e2;
  color: #ef4444;
}
.dcwi-series-detail {
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.dcwi-series-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.dcwi-series-detail-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dcwi-series-detail-name {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.dcwi-series-detail-val { font-size: 12px; }
.dcwi-series-detail-pos {
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}
.dcwi-series-color-hex {
  font-size: 12px;
  font-family: ui-monospace, monospace;
}
.dcwi-meta-collapse { margin-top: 4px; }
.dcwi-meta-tip { margin-bottom: 10px; }
.dcwi-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}
.muted { color: #9ca3af; }
</style>

<style>
.dcwi-drawer {
  z-index: 5000 !important;
}
.dcwi-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 14px 16px 10px;
  border-bottom: 1px solid #f0f0f0;
}
.dcwi-drawer .el-drawer__body {
  padding: 12px 16px 8px;
  overflow-y: auto;
}
.dcwi-drawer .el-drawer__footer {
  padding: 12px 16px 16px;
  border-top: 1px solid #f0f0f0;
}
</style>
