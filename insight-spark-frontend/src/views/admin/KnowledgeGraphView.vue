<template>
  <section class="knowledge-layout">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>知识图谱同步</h2>
          <p>将上传表、官方 Schema、敏感字段和诊断报告沉淀为节点与关系，作为 GraphRAG 检索上下文。</p>
        </div>
        <div class="audit-toolbar">
          <el-input v-model="graphSearchKeyword" placeholder="搜索表、字段、报告、敏感标签" clearable @keyup.enter="searchGraph" />
          <el-button type="primary" :loading="graphLoading" @click="rebuildGraph">同步图谱</el-button>
          <el-button @click="loadGraphOverview">刷新</el-button>
        </div>
      </div>

      <div class="knowledge-metrics">
        <div class="metric-panel mini">
          <div class="metric-label">节点数</div>
          <div class="metric-value">{{ graphOverview.nodeCount || 0 }}</div>
        </div>
        <div class="metric-panel mini">
          <div class="metric-label">关系数</div>
          <div class="metric-value">{{ graphOverview.edgeCount || 0 }}</div>
        </div>
        <div class="metric-panel mini">
          <div class="metric-label">检索结果</div>
          <div class="metric-value">{{ graphSearchResult.ragContext?.length || 0 }}</div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>知识文档</h2>
          <p>上传销售复盘、运营说明等 .txt / .md 文档，系统会切片并作为诊断报告的 GraphRAG 证据。</p>
        </div>
        <el-button @click="loadKnowledgeDocs">刷新文档</el-button>
      </div>
      <el-upload :auto-upload="false" :show-file-list="true" accept=".txt,.md" :limit="1" :on-change="onKnowledgeDocChange">
        <el-button>选择知识文档</el-button>
      </el-upload>
      <div class="upload-actions">
        <el-button type="primary" :disabled="!knowledgeDocFile" @click="uploadKnowledgeDoc">上传并切片</el-button>
      </div>
      <el-table :data="knowledgeDocs" height="260" empty-text="暂无知识文档">
        <el-table-column prop="title" label="文档标题" min-width="180" />
        <el-table-column prop="fileName" label="文件名" min-width="180" />
        <el-table-column prop="chunkCount" label="切片数" width="90" />
        <el-table-column prop="createdAt" label="上传时间" min-width="180" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" @click="indexKnowledgeDoc(row)">重建索引</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>GraphRAG 检索结果</h2>
          <p>对话分析会携带相关节点，帮助解释字段、敏感信息与历史诊断上下文。</p>
        </div>
        <el-button @click="searchGraph">检索</el-button>
      </div>
      <el-table :data="graphSearchResult.ragContext || []" height="360" empty-text="暂无检索结果">
        <el-table-column prop="nodeType" label="类型" width="150" />
        <el-table-column prop="label" label="名称" min-width="180" />
        <el-table-column prop="sourceType" label="来源" width="110" />
        <el-table-column prop="sourceId" label="来源ID" min-width="180" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="320" show-overflow-tooltip />
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>图谱节点</h2>
          <p>当前轻量版用 MySQL 存储节点和边，后续可平滑迁移到 Neo4j。</p>
        </div>
      </div>
      <el-table :data="graphSearchResult.nodes || []" height="420" empty-text="暂无图谱节点">
        <el-table-column prop="nodeType" label="类型" width="150" />
        <el-table-column prop="label" label="名称" min-width="180" />
        <el-table-column prop="sourceType" label="来源" width="110" />
        <el-table-column prop="content" label="说明" min-width="360" show-overflow-tooltip />
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>图谱关系</h2>
          <p>包括数据源-表、表-字段、字段-敏感标签、报告-数据表等多跳关系。</p>
        </div>
      </div>
      <el-table :data="graphSearchResult.edges || []" height="420" empty-text="暂无图谱关系">
        <el-table-column prop="relationType" label="关系" width="150" />
        <el-table-column prop="fromKey" label="起点" min-width="260" show-overflow-tooltip />
        <el-table-column prop="toKey" label="终点" min-width="260" show-overflow-tooltip />
        <el-table-column prop="weight" label="权重" width="90" />
      </el-table>
    </div>
  </section>
</template>

<script setup>
import { inject } from 'vue'

const {
  graphOverview,
  graphSearchKeyword,
  graphSearchResult,
  graphLoading,
  knowledgeDocFile,
  knowledgeDocs,
  loadGraphOverview,
  rebuildGraph,
  searchGraph,
  onKnowledgeDocChange,
  loadKnowledgeDocs,
  uploadKnowledgeDoc,
  indexKnowledgeDoc
} = inject('workbench')
</script>
