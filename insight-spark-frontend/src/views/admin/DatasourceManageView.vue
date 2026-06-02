<template>
  <section class="datasource-page">
    <div class="datasource-heading">
      <div>
        <h1>官方数据源管理</h1>
        <p>统一接入企业官方数据库，完成连接测试、Schema 解析、字段语义维护、权限绑定映射、行级隔离、Neo4j 同步与联邦跨库配置。</p>
      </div>
      <div class="heading-actions">
        <el-button type="primary" :icon="Plus" @click="drawerVisible = true">新增数据源</el-button>
        <el-tooltip
          content="批量读取已接入数据源的表结构、字段类型、字段注释和估算行数，生成或更新数据字典。"
          placement="bottom"
        >
          <el-button :icon="Grid" @click="batchSyncSchema">批量解析 Schema</el-button>
        </el-tooltip>
        <el-tooltip
          content="将已解析的表结构、字段语义、主外键关系和业务关联同步至 Neo4j，支撑 Text-to-SQL 精准解析。"
          placement="bottom"
        >
          <el-button :icon="Share" @click="syncKnowledgeGraph">同步知识图谱</el-button>
        </el-tooltip>
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <div class="overview-grid">
      <article v-for="item in overviewCards" :key="item.label" class="overview-card">
        <div class="overview-icon" :class="item.tone">
          <component :is="item.icon" />
        </div>
        <div>
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </article>
    </div>

    <div class="main-grid">
      <section class="panel datasource-list-panel">
        <div class="panel-header">
          <div>
            <h2>官方数据源列表</h2>
            <p>先选择数据源，再围绕该数据源维护 Schema、权限、安全与图谱配置。</p>
          </div>
        </div>

        <div class="table-toolbar">
          <el-input v-model="searchKeyword" :prefix-icon="Search" placeholder="搜索数据源名称/数据库名" clearable />
          <el-select v-model="typeFilter" placeholder="全部类型">
            <el-option label="全部类型" value="ALL" />
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="POSTGRESQL" />
          </el-select>
          <el-select v-model="statusFilter" placeholder="全部状态">
            <el-option label="全部状态" value="ALL" />
            <el-option label="启用" value="ENABLED" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </div>

        <el-table
          :data="filteredDatasources"
          height="322"
          empty-text="暂无官方数据源"
          highlight-current-row
          :row-class-name="datasourceRowClass"
          @row-click="selectDatasource"
        >
          <el-table-column width="42">
            <template #default="{ row }">
              <el-radio :model-value="selectedDatasourceId" :label="row.id" />
            </template>
          </el-table-column>
          <el-table-column prop="name" label="数据源名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="数据库类型" width="110">
            <template #default="{ row }">{{ dbTypeText(row.dbType) }}</template>
          </el-table-column>
          <el-table-column prop="databaseName" label="数据库名" min-width="130" show-overflow-tooltip />
          <el-table-column label="连接状态" width="110">
            <template #default="{ row }">
              <span class="dot-status" :class="connectionTone(row)"></span>
              {{ connectionText(row) }}
            </template>
          </el-table-column>
          <el-table-column label="Schema 状态" width="120">
            <template #default="{ row }">
              <el-tag :type="schemaTables.length && row.id === selectedDatasourceId ? 'success' : 'warning'" size="small">
                {{ schemaTables.length && row.id === selectedDatasourceId ? '已解析' : '待解析' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="图谱同步" width="105">
            <template #default="{ row }">
              <el-tag :type="row.lastSyncAt ? 'success' : 'warning'" size="small">
                {{ row.lastSyncAt ? '已同步' : '未同步' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="150">
            <template #default="{ row }">{{ formatTime(row.updatedAt || row.createTime || row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="166" fixed="right">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button link type="primary" @click.stop="selectDatasource(row)">详情</el-button>
                <el-button link type="primary" @click.stop="testDatasource(row.id)">测试</el-button>
                <el-dropdown trigger="click" @command="handleDatasourceCommand">
                  <el-button link type="primary" @click.stop>
                    更多<el-icon class="more-icon"><MoreFilled /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item :command="`sync:${row.id}`">解析 Schema</el-dropdown-item>
                      <el-dropdown-item :command="`edit:${row.id}`">编辑连接</el-dropdown-item>
                      <el-dropdown-item :command="`toggle:${row.id}`">{{ row.status === 'ENABLED' ? '禁用数据源' : '启用数据源' }}</el-dropdown-item>
                      <el-dropdown-item divided :command="`delete:${row.id}`">删除数据源</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <aside class="panel detail-panel">
        <div class="detail-header">
          <div class="detail-title-wrap">
            <h2>数据源详情<span v-if="selectedDatasource">（{{ selectedDatasource.name }}）</span></h2>
            <el-tag v-if="selectedDatasource" :type="selectedDatasource.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ selectedDatasource.status === 'ENABLED' ? '启用' : '禁用' }}
            </el-tag>
          </div>
          <el-button v-if="selectedDatasource" :icon="Edit" @click="openEditDrawer(selectedDatasource)">编辑配置</el-button>
        </div>

        <el-empty v-if="!selectedDatasource" description="请从左侧选择一个官方数据源" />
        <template v-else>
          <div class="detail-body">
            <dl class="detail-list">
              <dt>数据源名称：</dt><dd>{{ selectedDatasource.name }}</dd>
              <dt>数据库类型：</dt><dd>{{ dbTypeText(selectedDatasource.dbType) }}</dd>
              <dt>数据库名：</dt><dd>{{ selectedDatasource.databaseName || '-' }}</dd>
              <dt>主机地址：</dt><dd>{{ selectedDatasource.host }}:{{ selectedDatasource.port }}</dd>
              <dt>用户名：</dt><dd>{{ selectedDatasource.username || '-' }}</dd>
              <dt>连接状态：</dt>
              <dd><span class="dot-status" :class="connectionTone(selectedDatasource)"></span>{{ connectionText(selectedDatasource) }} <span class="status-code">（{{ selectedDatasource.lastTestStatus || 'UNKNOWN' }}）</span></dd>
            </dl>
            <dl class="detail-list detail-list-secondary">
              <dt>Schema 状态：</dt><dd>{{ schemaTables.length ? `已解析（${schemaTables.length} 张表）` : '待解析' }}</dd>
              <dt>字段数量：</dt><dd>{{ schemaFields.length || selectedSchemaFieldCount }} 个</dd>
              <dt>图谱同步状态：</dt><dd>{{ selectedDatasource.lastSyncAt ? '已同步' : '未同步' }}</dd>
              <dt>最近同步时间：</dt><dd>{{ formatTime(selectedDatasource.lastSyncAt) }}</dd>
              <dt>授权对象：</dt><dd>{{ datasourcePermissions.length }} 个对象</dd>
              <dt>行级规则：</dt><dd>{{ rowPolicies.length }} 条规则</dd>
            </dl>
          </div>

          <div class="quick-actions-block">
            <h3>快捷操作</h3>
            <div class="quick-actions">
              <el-button type="primary" plain @click="testDatasource(selectedDatasource.id)">测试连接</el-button>
              <el-button type="success" plain @click="syncDatasourceSchema(selectedDatasource.id)">解析 Schema</el-button>
              <el-button class="kg-button" plain @click="syncKnowledgeGraph">同步知识图谱</el-button>
              <el-button type="danger" plain @click="toggleDatasource(selectedDatasource)">
              {{ selectedDatasource.status === 'ENABLED' ? '禁用数据源' : '启用数据源' }}
              </el-button>
            </div>
          </div>
        </template>
      </aside>
    </div>

    <section class="panel config-panel">
      <el-tabs v-model="activeConfigTab">
        <el-tab-pane name="schema">
          <template #label><span class="tab-label"><Document />Schema 表结构</span></template>
          <div class="schema-split">
            <div class="schema-pane">
              <div class="section-title">
                <h3>表列表（{{ schemaTables.length }}）</h3>
                <el-button :disabled="!selectedDatasourceId" @click="syncDatasourceSchema(selectedDatasourceId)">重新解析</el-button>
              </div>
              <el-table :data="pagedSchemaTables" height="520" empty-text="请选择数据源并解析 Schema" @row-click="selectSchemaTable">
                <el-table-column label="表名" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="table-name-cell">{{ row.tableName }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="表注释" min-width="150" show-overflow-tooltip>
                  <template #default="{ row }">{{ cleanComment(row.tableComment) || '-' }}</template>
                </el-table-column>
                <el-table-column prop="fieldCount" label="字段数" width="90" />
                <el-table-column prop="tableRows" label="估算行数" width="105" />
                <el-table-column label="操作" width="118">
                  <template #default="{ row }">
                    <div class="inline-actions">
                      <el-button link type="primary" @click.stop="selectSchemaTable(row)">字段</el-button>
                      <el-button link type="primary" @click.stop="syncKnowledgeGraph">同步</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <div class="schema-pagination">
                <el-pagination
                  v-model:current-page="schemaTablePage"
                  v-model:page-size="schemaTablePageSize"
                  :page-sizes="[10, 20, 50]"
                  :total="schemaTables.length"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                />
              </div>
            </div>
            <div class="schema-pane">
              <div class="section-title">
                <h3>表字段详情</h3>
                <span>{{ schemaFields.length }} 个字段</span>
              </div>
              <el-table :data="schemaFields" height="574" empty-text="请选择 Schema 表">
                <el-table-column prop="columnName" label="字段名" min-width="150" />
                <el-table-column prop="dataType" label="类型" width="115" />
                <el-table-column prop="columnComment" label="数据库注释" min-width="140" show-overflow-tooltip />
                <el-table-column label="敏感级别" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.sensitive ? 'danger' : 'info'">{{ row.sensitive ? '敏感字段' : '普通字段' }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="semantics">
          <template #label><span class="tab-label"><DataLine />字段语义配置</span></template>
          <el-table :data="schemaFields" height="330" empty-text="请选择 Schema 表">
            <el-table-column prop="columnName" label="字段名" min-width="130" />
            <el-table-column prop="dataType" label="类型" width="110" />
            <el-table-column prop="columnComment" label="数据库注释" min-width="150" show-overflow-tooltip />
            <el-table-column label="业务名称" min-width="160">
              <template #default="{ row }">
                <el-input v-model="row.businessName" size="small" @change="updateSchemaField(row)" />
              </template>
            </el-table-column>
            <el-table-column label="业务含义" min-width="210">
              <template #default="{ row }">
                <el-input v-model="row.businessDesc" size="small" @change="updateSchemaField(row)" />
              </template>
            </el-table-column>
            <el-table-column label="同义词" min-width="170">
              <template #default="{ row }">
                <el-input v-model="row.synonyms" size="small" placeholder="逗号分隔" @change="updateSchemaField(row)" />
              </template>
            </el-table-column>
            <el-table-column label="图谱同步" width="105">
              <template #default="{ row }">
                <el-switch v-model="row.kgSyncEnabled" @change="updateSchemaField(row)" />
              </template>
            </el-table-column>
            <el-table-column label="敏感" width="88">
              <template #default="{ row }">
                <el-switch v-model="row.sensitive" :active-value="1" :inactive-value="0" @change="updateSchemaField(row)" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="permissions">
          <template #label><span class="tab-label"><Key />权限绑定</span></template>
          <el-alert title="官方数据源默认强制只读，仅允许 SELECT 查询。" type="info" :closable="false" class="tab-alert" />
          <div class="form-table-grid">
            <el-form label-position="top" class="config-form federal-form">
              <el-form-item label="授权范围">
                <el-select v-model="datasourcePermissionForm.tableName" class="full-width" placeholder="请选择官方表">
                  <el-option label="整个数据源（全部表）" value="*" />
                  <el-option v-for="item in schemaTables" :key="item.tableName" :label="formatTableOption(item)" :value="item.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item label="授权对象类型">
                <el-select v-model="datasourcePermissionForm.principalType" class="full-width">
                  <el-option label="用户" value="USER" />
                  <el-option label="角色" value="ROLE" />
                </el-select>
              </el-form-item>
              <el-form-item label="授权对象">
                <el-input v-model="datasourcePermissionForm.principalId" placeholder="user / analyst-role" />
              </el-form-item>
              <el-form-item label="权限类型">
                <el-input v-model="datasourcePermissionForm.permissionType" disabled />
              </el-form-item>
              <el-button type="primary" @click="grantDatasourcePermission">绑定权限</el-button>
            </el-form>
            <el-table :data="datasourcePermissions" height="282" empty-text="请选择数据源">
              <el-table-column label="授权范围" min-width="130">
                <template #default="{ row }">{{ row.tableName === '*' ? '整个数据源' : row.tableName }}</template>
              </el-table-column>
              <el-table-column prop="principalType" label="对象类型" width="100" />
              <el-table-column prop="principalId" label="对象名称" min-width="130" />
              <el-table-column prop="permissionType" label="权限" width="90" />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button link type="danger" @click="revokeDatasourcePermission(row.id)">回收</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane name="row-policy">
          <template #label><span class="tab-label"><Lock />行级隔离规则</span></template>
          <div class="form-table-grid">
            <el-form label-position="top" class="config-form">
              <el-alert title="推荐按角色配置数据范围；用户登录后会继承其角色对应的行级过滤条件。" type="info" :closable="false" class="tab-alert" />
              <el-form-item label="适用表">
                <el-input v-model="rowPolicyForm.tableName" placeholder="留空表示当前数据源所有表" />
              </el-form-item>
              <el-form-item label="对象类型">
                <el-select v-model="rowPolicyForm.principalType" class="full-width">
                  <el-option label="角色" value="ROLE" />
                  <el-option label="用户" value="USER" />
                </el-select>
              </el-form-item>
              <el-form-item label="角色编码 / 用户账号">
                <el-input v-model="rowPolicyForm.principalId" placeholder="例如 sales_east / finance_manager" />
              </el-form-item>
              <el-form-item label="过滤条件">
                <el-input v-model="rowPolicyForm.filterExpression" placeholder="例如 region = '华东'，系统会自动追加到 WHERE 条件" />
              </el-form-item>
              <el-form-item label="是否启用">
                <el-switch v-model="rowPolicyForm.enabled" />
              </el-form-item>
              <el-button type="primary" @click="saveRowPolicy">保存行级规则</el-button>
            </el-form>
            <el-table :data="rowPolicies" height="300" empty-text="暂无行级隔离规则">
              <el-table-column label="表名" min-width="130">
                <template #default="{ row }">{{ row.tableName === '*' ? '全部表' : row.tableName }}</template>
              </el-table-column>
              <el-table-column prop="principalType" label="对象类型" width="100" />
              <el-table-column prop="principalId" label="角色/账号" min-width="130" />
              <el-table-column prop="filterExpression" label="过滤条件" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button link type="danger" @click="deleteRowPolicy(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane name="federal">
          <template #label><span class="tab-label"><Link />联邦跨库关联</span></template>
          <div class="form-table-grid wide-form">
            <el-form label-position="top" class="config-form">
              <el-form-item label="官方表">
                <el-select v-model="federalForm.leftTable" class="full-width" placeholder="请选择官方表" @change="handleFederalLeftTableChange">
                  <el-option v-for="item in schemaTables" :key="item.tableName" :label="formatTableOption(item)" :value="item.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item label="官方字段">
                <el-select v-model="federalForm.leftField" class="full-width" placeholder="请选择官方字段" filterable>
                  <el-option v-for="item in federalOfficialFields" :key="item.columnName" :label="formatFieldOption(item)" :value="item.columnName" />
                </el-select>
              </el-form-item>
              <el-form-item label="上传表">
                <el-select v-model="federalForm.rightTable" class="full-width" placeholder="请选择上传表" filterable @change="handleFederalRightTableChange">
                  <el-option v-for="item in uploadTableOptions" :key="item.tableName" :label="item.displayName ? `${item.displayName}（${item.tableName}）` : item.tableName" :value="item.tableName" />
                </el-select>
              </el-form-item>
              <el-form-item label="上传字段">
                <el-select v-model="federalForm.rightField" class="full-width" placeholder="请选择上传字段" filterable>
                  <el-option v-for="item in federalRightFields" :key="item.columnName" :label="item.displayName ? `${item.displayName}（${item.columnName}）` : item.columnName" :value="item.columnName" />
                </el-select>
              </el-form-item>
              <el-form-item label="关联类型">
                <el-select v-model="federalForm.relationType" class="full-width">
                  <el-option label="左连接" value="LEFT_JOIN" />
                  <el-option label="内连接" value="INNER_JOIN" />
                </el-select>
              </el-form-item>
              <div class="button-row">
                <el-button @click="validateFederalRelation">校验关联</el-button>
                <el-button type="primary" @click="saveFederalRelation">保存关联</el-button>
                <el-button type="success" @click="generateFederalSql">生成联邦 SQL 计划</el-button>
              </div>
            </el-form>
            <div>
              <el-alert v-if="federalSqlPreview" title="Agent 联邦 SQL 计划" type="success" :closable="false" class="federal-preview">
                <pre>{{ federalSqlPreview }}</pre>
              </el-alert>
              <el-table :data="federalRelations" height="300" empty-text="暂无联邦关联">
                <el-table-column prop="leftTable" label="官方表" min-width="130" />
                <el-table-column prop="leftField" label="官方字段" width="120" />
                <el-table-column prop="rightTable" label="上传表" min-width="130" />
                <el-table-column prop="rightField" label="上传字段" width="120" />
                <el-table-column prop="relationType" label="类型" width="110" />
                <el-table-column label="操作" width="90">
                  <template #default="{ row }">
                    <el-button link type="danger" @click="deleteFederalRelation(row.id)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="neo4j">
          <template #label><span class="tab-label"><Cpu />Neo4j 配置</span></template>
          <div class="neo4j-layout">
            <el-form label-position="top" class="config-form neo4j-form">
              <el-form-item label="Neo4j 地址">
                <el-input v-model="neo4jConfig.uri" placeholder="bolt://localhost:7687" />
              </el-form-item>
              <el-form-item label="账号">
                <el-input v-model="neo4jConfig.username" placeholder="neo4j" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="neo4jConfig.password" type="password" show-password placeholder="留空则不修改密码" />
              </el-form-item>
              <el-form-item label="数据库">
                <el-input v-model="neo4jConfig.databaseName" placeholder="neo4j" />
              </el-form-item>
              <el-form-item label="同步策略">
                <el-input v-model="neo4jConfig.syncRule" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="启用同步">
                <el-switch v-model="neo4jConfig.enabled" />
              </el-form-item>
              <div class="button-row">
                <el-button type="primary" @click="saveNeo4jConfig">保存配置</el-button>
                <el-button type="success" @click="syncKnowledgeGraph">立即同步</el-button>
                <el-button @click="loadNeo4jConfig">刷新</el-button>
              </div>
            </el-form>
            <div class="sync-summary">
              <h3>同步状态</h3>
              <div class="summary-grid">
                <div class="sync-stat">
                  <span>最近同步时间</span>
                  <strong>{{ formatTime(selectedDatasource?.lastSyncAt) }}</strong>
                </div>
                <div class="sync-stat">
                  <span>同步表数量</span>
                  <strong>{{ schemaTables.length }}</strong>
                </div>
                <div class="sync-stat">
                  <span>同步字段数量</span>
                  <strong>{{ selectedSchemaFieldCount || schemaFields.length }}</strong>
                </div>
                <div class="sync-stat">
                  <span>同步关系数量</span>
                  <strong>{{ lastKnowledgeGraphSync.edgeCount ?? federalRelations.length }}</strong>
                </div>
                <div class="sync-stat">
                  <span>同步状态</span>
                  <strong>
                    <span class="dot-status" :class="neo4jConfig.enabled ? 'success' : 'muted'"></span>
                    {{ neo4jConfig.enabled ? '启用' : '停用' }}
                  </strong>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-drawer v-model="drawerVisible" :title="editingDatasource ? '编辑官方数据源' : '新增官方数据源'" size="520px" class="datasource-drawer">
      <el-steps :active="drawerStep" finish-status="success" simple>
        <el-step title="基础信息" />
        <el-step title="连接配置" />
        <el-step title="连接池" />
        <el-step title="测试解析" />
      </el-steps>

      <el-form :model="datasourceForm" label-position="top" class="drawer-form">
        <template v-if="drawerStep === 0">
          <el-form-item label="数据源名称">
            <el-input v-model="datasourceForm.name" placeholder="例如：企业销售库" />
          </el-form-item>
          <el-form-item label="数据库类型">
            <el-select v-model="datasourceForm.dbType" class="full-width">
              <el-option label="MySQL" value="MYSQL" />
              <el-option label="PostgreSQL" value="POSTGRESQL" />
            </el-select>
          </el-form-item>
          <el-form-item label="知识图谱同步规则">
            <el-input v-model="datasourceForm.kgSyncRule" type="textarea" :rows="4" placeholder="同步表字段关系、业务含义、同义词、敏感标识与联邦关联" />
          </el-form-item>
        </template>

        <template v-else-if="drawerStep === 1">
          <el-form-item label="主机">
            <el-input v-model="datasourceForm.host" placeholder="localhost" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input v-model="datasourceForm.port" placeholder="3306" />
          </el-form-item>
          <el-form-item label="数据库名">
            <el-input v-model="datasourceForm.databaseName" placeholder="insight_spark" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="datasourceForm.username" placeholder="root" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="datasourceForm.password" type="password" show-password placeholder="数据库密码" />
          </el-form-item>
        </template>

        <template v-else-if="drawerStep === 2">
          <el-form-item label="最大连接数">
            <el-input v-model="datasourceForm.poolMaxSize" placeholder="10" />
          </el-form-item>
          <el-form-item label="连接超时（ms）">
            <el-input v-model="datasourceForm.poolTimeoutMs" placeholder="30000" />
          </el-form-item>
        </template>

        <template v-else>
          <el-alert title="保存后可立即测试连接并解析 Schema。" type="info" :closable="false" />
          <div class="drawer-final-actions">
            <el-button :disabled="!editingDatasource" @click="testDatasource(editingDatasource?.id)">测试连接</el-button>
            <el-button :disabled="!editingDatasource" @click="syncDatasourceSchema(editingDatasource?.id)">解析 Schema</el-button>
          </div>
        </template>
      </el-form>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button :disabled="drawerStep === 0" @click="drawerStep -= 1">上一步</el-button>
          <el-button v-if="drawerStep < 3" type="primary" @click="drawerStep += 1">下一步</el-button>
          <el-button v-else type="primary" @click="saveDrawerDatasource">保存并启用数据源</el-button>
        </div>
      </template>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  CircleCheck,
  Connection,
  Cpu,
  DataLine,
  Document,
  Edit,
  Grid,
  Key,
  Link,
  Lock,
  MoreFilled,
  Plus,
  Refresh,
  Search,
  Share
} from '@element-plus/icons-vue'

const {
  API_BASE,
  createDatasource,
  datasourceForm,
  datasourceHealthMap,
  datasourcePermissions,
  datasourcePermissionForm,
  deleteDatasource,
  deleteFederalRelation,
  federalForm,
  federalRelations,
  fillCurrentDatasource,
  grantDatasourcePermission,
  loadDatasourceHealth,
  loadDatasourcePermissions,
  loadDatasources,
  loadFederalRelations,
  loadSchemaTables,
  loadTables,
  loadTables: loadUploadTables,
  officialDatasources,
  revokeDatasourcePermission,
  schemaFields,
  schemaTables,
  selectDatasource,
  selectSchemaTable,
  selectedDatasourceId,
  saveFederalRelation,
  syncDatasourceSchema,
  tables,
  testDatasource,
  toggleDatasource,
  unwrap,
  updateDatasource,
  updateSchemaField,
  validateFederalRelation
} = inject('workbench')

const searchKeyword = ref('')
const typeFilter = ref('ALL')
const statusFilter = ref('ALL')
const activeConfigTab = ref('schema')
const drawerVisible = ref(false)
const drawerStep = ref(0)
const editingDatasource = ref(null)
const schemaTablePage = ref(1)
const schemaTablePageSize = ref(10)

const rowPolicies = ref([])
const rowPolicyForm = ref({
  tableName: '',
  principalType: 'ROLE',
  principalId: 'sales_east',
  filterExpression: '',
  enabled: true
})
const neo4jConfig = ref({
  uri: 'bolt://localhost:7687',
  username: 'neo4j',
  password: '',
  databaseName: 'neo4j',
  syncRule: '同步官方数据源表、字段、业务含义、同义词、敏感标识与联邦关系',
  enabled: true
})
const federalSqlPreview = ref('')
const federalOfficialFields = ref([])
const federalRightFields = ref([])
const lastKnowledgeGraphSync = ref({
  nodeCount: null,
  edgeCount: null
})

const uploadTableOptions = computed(() => tables.value.filter(item => item.sourceType !== 'OFFICIAL'))

const selectedDatasource = computed(() =>
  officialDatasources.value.find(item => item.id === selectedDatasourceId.value) || null
)

const filteredDatasources = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  return officialDatasources.value.filter(item => {
    const matchKeyword = !keyword ||
      String(item.name || '').toLowerCase().includes(keyword) ||
      String(item.databaseName || '').toLowerCase().includes(keyword)
    const matchType = typeFilter.value === 'ALL' || item.dbType === typeFilter.value
    const matchStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
    return matchKeyword && matchType && matchStatus
  })
})

const selectedSchemaFieldCount = computed(() => {
  const countFromTables = schemaTables.value.reduce((total, item) => total + Number(item.fieldCount || item.columnCount || 0), 0)
  return countFromTables || schemaFields.value.length
})

const pagedSchemaTables = computed(() => {
  const start = (schemaTablePage.value - 1) * schemaTablePageSize.value
  return schemaTables.value.slice(start, start + schemaTablePageSize.value)
})

const overviewCards = computed(() => {
  const total = officialDatasources.value.length
  const enabled = officialDatasources.value.filter(item => item.status === 'ENABLED').length
  const healthy = officialDatasources.value.filter(item => item.lastTestStatus === 'SUCCESS' || datasourceHealthMap.value[item.id]?.poolCreated).length
  const pending = Math.max(total - schemaTables.value.length ? officialDatasources.value.filter(item => item.id !== selectedDatasourceId.value).length : 0, 0)
  return [
    { label: '数据源总数', value: total, icon: DataLine, tone: 'blue' },
    { label: '启用中', value: enabled, icon: CircleCheck, tone: 'green' },
    { label: '连接正常', value: healthy, icon: Connection, tone: 'purple' },
    { label: '待解析 Schema', value: pending, icon: Document, tone: 'orange' }
  ]
})

const dbTypeText = (value) => {
  if (value === 'MYSQL') return 'MySQL'
  if (value === 'POSTGRESQL') return 'PostgreSQL'
  return value || '-'
}

const connectionText = (row) => {
  if (row.lastTestStatus === 'SUCCESS') return '正常'
  if (row.lastTestStatus === 'FAILED') return '异常'
  if (datasourceHealthMap.value[row.id]?.poolCreated) return '正常'
  return '未测试'
}

const connectionTone = (row) => {
  if (row.lastTestStatus === 'SUCCESS' || datasourceHealthMap.value[row.id]?.poolCreated) return 'success'
  if (row.lastTestStatus === 'FAILED') return 'danger'
  return 'muted'
}

const formatTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}

const datasourceRowClass = ({ row }) => row.id === selectedDatasourceId.value ? 'selected-row' : ''

const cleanComment = (value) => {
  const text = String(value || '').trim()
  if (!text) return ''
  return /[�æçåèéäöü]/.test(text) ? '' : text
}

const formatTableOption = (item) => {
  const comment = cleanComment(item.tableComment)
  return comment ? `${item.tableName}（${comment}）` : item.tableName
}
const formatFieldOption = (item) => {
  const comment = cleanComment(item.businessName || item.columnComment)
  return comment ? `${item.columnName}（${comment}）` : item.columnName
}

const resetDatasourceForm = () => {
  datasourceForm.value = {
    name: '',
    dbType: 'MYSQL',
    host: 'localhost',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    poolMaxSize: 10,
    poolTimeoutMs: 30000,
    kgSyncRule: '同步表、字段、业务含义、同义词、敏感标识与联邦关系'
  }
}

const openEditDrawer = (row) => {
  editingDatasource.value = row
  drawerStep.value = 0
  datasourceForm.value = { ...row, password: '' }
  drawerVisible.value = true
}

const saveDrawerDatasource = async () => {
  try {
    if (editingDatasource.value?.id) {
      await updateDatasource({ ...editingDatasource.value, ...datasourceForm.value })
    } else {
      await createDatasource()
    }
    drawerVisible.value = false
    editingDatasource.value = null
    drawerStep.value = 0
    resetDatasourceForm()
  } catch (error) {
    ElMessage.error(error.message || '保存数据源失败')
  }
}

const handleDatasourceCommand = async (command) => {
  const [action, idText] = String(command).split(':')
  const id = Number(idText)
  const row = officialDatasources.value.find(item => item.id === id)
  if (!row) return
  if (action === 'sync') await syncDatasourceSchema(id)
  if (action === 'edit') openEditDrawer(row)
  if (action === 'toggle') await toggleDatasource(row)
  if (action === 'delete') await deleteDatasource(row)
}

const refreshAll = async () => {
  await loadDatasources()
  if (selectedDatasourceId.value) {
    await Promise.all([
      loadSchemaTables(selectedDatasourceId.value),
      loadDatasourcePermissions(selectedDatasourceId.value),
      loadFederalRelations(selectedDatasourceId.value),
      loadRowPolicies(selectedDatasourceId.value)
    ])
  }
}

const batchSyncSchema = async () => {
  const enabledSources = officialDatasources.value.filter(item => item.status === 'ENABLED')
  if (!enabledSources.length) {
    ElMessage.warning('暂无启用中的数据源')
    return
  }
  for (const item of enabledSources) {
    await syncDatasourceSchema(item.id)
  }
}

const handleFederalLeftTableChange = async (tableName) => {
  federalForm.value.leftField = ''
  federalOfficialFields.value = []
  if (!selectedDatasourceId.value || !tableName) return
  try {
    federalOfficialFields.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/schema/tables/${tableName}/fields`))
  } catch (error) {
    ElMessage.error(error.message || '加载官方字段失败')
  }
}

const handleFederalRightTableChange = async (tableName) => {
  federalForm.value.rightField = ''
  federalRightFields.value = []
  if (!tableName) return
  try {
    federalRightFields.value = unwrap(await axios.get(`${API_BASE}/api/data/tables/${tableName}/fields`))
  } catch (error) {
    ElMessage.error(error.message || '加载上传字段失败')
  }
}

const loadRowPolicies = async (datasourceId = selectedDatasourceId.value) => {
  if (!datasourceId) return
  try {
    rowPolicies.value = unwrap(await axios.get(`${API_BASE}/api/datasources/${datasourceId}/row-policies`))
  } catch (error) {
    ElMessage.error(error.message || '加载行级规则失败')
  }
}

const saveRowPolicy = async () => {
  if (!selectedDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  try {
    const payload = {
      ...rowPolicyForm.value,
      tableName: String(rowPolicyForm.value.tableName || '').trim() || '*'
    }
    await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/row-policies`, payload).then(unwrap)
    ElMessage.success('行级规则已保存')
    rowPolicyForm.value.filterExpression = ''
    await loadRowPolicies()
  } catch (error) {
    ElMessage.error(error.message || '保存行级规则失败')
  }
}

const deleteRowPolicy = async (policyId) => {
  try {
    await axios.post(`${API_BASE}/api/datasources/row-policies/${policyId}/delete`).then(unwrap)
    ElMessage.success('行级规则已删除')
    await loadRowPolicies()
  } catch (error) {
    ElMessage.error(error.message || '删除行级规则失败')
  }
}

const loadNeo4jConfig = async () => {
  try {
    const config = unwrap(await axios.get(`${API_BASE}/api/datasources/neo4j-config`))
    neo4jConfig.value = { ...neo4jConfig.value, ...config, password: '' }
  } catch (error) {
    ElMessage.error(error.message || '加载 Neo4j 配置失败')
  }
}

const saveNeo4jConfig = async () => {
  try {
    const config = unwrap(await axios.post(`${API_BASE}/api/datasources/neo4j-config`, neo4jConfig.value))
    neo4jConfig.value = { ...neo4jConfig.value, ...config, password: '' }
    ElMessage.success('Neo4j 配置已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存 Neo4j 配置失败')
  }
}

const syncKnowledgeGraph = async () => {
  try {
    const result = unwrap(await axios.post(`${API_BASE}/api/datasources/sync-knowledge-graph`))
    const nodeCount = result.nodeUpsertCount ?? 0
    const edgeCount = result.edgeUpsertCount ?? 0
    lastKnowledgeGraphSync.value = { nodeCount, edgeCount }
    ElMessage.success(`知识图谱已同步：${nodeCount} 个节点，${edgeCount} 条关系`)
    await loadDatasources()
    if (selectedDatasourceId.value) {
      await loadSchemaTables(selectedDatasourceId.value)
    }
  } catch (error) {
    ElMessage.error(error.message || '同步知识图谱失败')
  }
}

const generateFederalSql = async () => {
  if (!selectedDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  try {
    const plan = unwrap(await axios.post(`${API_BASE}/api/datasources/${selectedDatasourceId.value}/federal-sql`, {
      uploadTable: federalForm.value?.rightTable || '',
      question: '联邦跨库分析'
    }))
    federalSqlPreview.value = JSON.stringify(plan, null, 2)
  } catch (error) {
    ElMessage.error(error.message || '生成联邦 SQL 计划失败')
  }
}

watch(drawerVisible, (visible) => {
  if (visible && !editingDatasource.value) {
    resetDatasourceForm()
    drawerStep.value = 0
  }
})

watch(selectedDatasourceId, (datasourceId) => {
  rowPolicies.value = []
  federalSqlPreview.value = ''
  federalOfficialFields.value = []
  federalRightFields.value = []
  schemaTablePage.value = 1
  federalForm.value.leftTable = ''
  federalForm.value.leftField = ''
  federalForm.value.rightTable = ''
  federalForm.value.rightField = ''
  loadRowPolicies(datasourceId)
})

watch([schemaTables, schemaTablePageSize], () => {
  const maxPage = Math.max(1, Math.ceil(schemaTables.value.length / schemaTablePageSize.value))
  if (schemaTablePage.value > maxPage) {
    schemaTablePage.value = maxPage
  }
})

onMounted(async () => {
  await loadNeo4jConfig()
  if (!tables.value.length) {
    loadUploadTables()
  }
  if (!officialDatasources.value.length) {
    await loadDatasources()
  } else {
    for (const ds of officialDatasources.value) {
      if (!datasourceHealthMap.value[ds.id]) {
        loadDatasourceHealth(ds.id)
      }
    }
  }
})
</script>

<style scoped>
.datasource-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.datasource-heading,
.panel,
.overview-card {
  background: #fff;
  border: 1px solid #e5e9f2;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.datasource-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 24px;
  border-radius: 8px;
}

.datasource-heading h1 {
  margin: 0 0 8px;
  color: #17233d;
  font-size: 22px;
  line-height: 1.25;
}

.datasource-heading p,
.panel-header p {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.heading-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  min-width: 460px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.overview-card {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 86px;
  padding: 18px 20px;
  border-radius: 8px;
}

.overview-card span {
  display: block;
  margin-bottom: 6px;
  color: #475467;
  font-size: 13px;
}

.overview-card strong {
  color: #17233d;
  font-size: 26px;
  line-height: 1;
}

.overview-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
}

.overview-icon svg {
  width: 26px;
  height: 26px;
}

.overview-icon.blue { color: #1f6feb; background: #e8f1ff; }
.overview-icon.green { color: #16a34a; background: #e8f8ef; }
.overview-icon.purple { color: #7c3aed; background: #f1eaff; }
.overview-icon.orange { color: #f59e0b; background: #fff4df; }

.main-grid {
  display: grid;
  grid-template-columns: minmax(620px, 1.35fr) minmax(360px, 1fr);
  gap: 16px;
}

.panel {
  border-radius: 8px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 12px;
  border-bottom: 1px solid #eef2f7;
}

.datasource-list-panel .panel-header {
  padding: 0 0 12px;
  border-bottom: 1px solid #eef2f7;
}

.datasource-list-panel .panel-header h2 {
  margin-bottom: 0;
  font-size: 16px;
}

.datasource-list-panel .panel-header p {
  display: none;
}

.panel-header.compact {
  padding-bottom: 16px;
}

.panel-header h2 {
  margin: 0 0 6px;
  color: #17233d;
  font-size: 17px;
}

.table-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 150px 150px;
  gap: 10px;
  padding: 12px 0 10px;
}

.datasource-list-panel :deep(.el-input__wrapper),
.datasource-list-panel :deep(.el-select__wrapper) {
  min-height: 34px;
  border-radius: 6px;
}

.datasource-list-panel :deep(.el-table th.el-table__cell) {
  height: 40px;
  background: #f8fafc;
  color: #17233d;
  font-weight: 700;
}

.datasource-list-panel :deep(.el-table .el-table__cell) {
  padding: 7px 0;
}

.datasource-list-panel :deep(.el-table__body tr.current-row > td.el-table__cell),
.datasource-list-panel :deep(.el-table__body tr.selected-row > td.el-table__cell) {
  background: #eef5ff;
}

.datasource-list-panel :deep(.el-table__row.selected-row) {
  background: #eef5ff;
}

.datasource-list-panel {
  padding: 14px 16px 10px;
}

.table-actions {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}

.table-actions :deep(.el-button) {
  height: 24px;
  padding: 0;
  margin: 0;
  font-size: 13px;
}

.more-icon {
  margin-left: 2px;
  transform: rotate(90deg);
}

.detail-panel {
  min-height: 466px;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 56px;
  padding: 14px 18px;
  border-bottom: 1px solid #eef2f7;
}

.detail-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.detail-title-wrap h2 {
  margin: 0;
  color: #17233d;
  font-size: 17px;
  line-height: 1.4;
}

.detail-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 24px 18px 28px;
}

.detail-list {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 14px 14px;
  margin: 0;
}

.detail-list-secondary {
  padding-left: 24px;
  border-left: 1px solid #e5e9f2;
}

.detail-list dt {
  color: #344054;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.detail-list dd {
  min-width: 0;
  margin: 0;
  color: #1d2939;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  word-break: break-word;
}

.status-code {
  color: #475467;
  font-weight: 500;
}

.dot-status {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  border-radius: 50%;
  vertical-align: middle;
}

.dot-status.success { background: #16a34a; }
.dot-status.danger { background: #ef4444; }
.dot-status.muted { background: #98a2b3; }

.quick-actions-block {
  padding: 18px;
  border-top: 1px solid #eef2f7;
}

.quick-actions-block h3 {
  margin: 0 0 14px;
  color: #17233d;
  font-size: 15px;
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}

.quick-actions :deep(.el-button) {
  min-width: 96px;
  height: 34px;
  margin: 0;
  border-radius: 4px;
  font-weight: 600;
}

.quick-actions .kg-button {
  color: #8b5cf6;
  border-color: #c4b5fd;
  background: #faf5ff;
}

.quick-actions .kg-button:hover {
  color: #7c3aed;
  border-color: #a78bfa;
  background: #f3e8ff;
}

.config-panel {
  padding: 0 14px 14px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-label svg {
  width: 16px;
  height: 16px;
}

.schema-split,
.form-table-grid,
.neo4j-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  padding: 4px 2px 0;
}

.schema-pane {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.schema-pane :deep(.el-table) {
  border: 1px solid #edf1f7;
  border-radius: 6px;
}

.schema-pane .section-title {
  box-sizing: border-box;
  min-height: 40px;
  margin-bottom: 8px;
  align-items: center;
}

.schema-pane .section-title :deep(.el-button) {
  height: 32px;
  padding: 0 16px;
  margin: 0;
  border-radius: 6px;
}

.form-table-grid.wide-form {
  grid-template-columns: 430px minmax(520px, 1fr);
}

.schema-split :deep(.el-table th.el-table__cell) {
  height: 36px;
  background: #f8fafc;
}

.schema-split :deep(.el-table .el-table__cell) {
  padding: 7px 0;
}

.schema-split :deep(.el-table__row) {
  height: 44px;
}

.schema-split :deep(.cell) {
  line-height: 20px;
}

.table-name-cell {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.schema-pagination {
  display: flex;
  justify-content: flex-end;
  min-height: 54px;
  padding: 12px 2px 0;
}

.schema-pagination :deep(.el-pagination) {
  --el-pagination-button-width: 30px;
  --el-pagination-button-height: 30px;
  color: #667085;
}

.schema-pagination :deep(.el-select__wrapper),
.schema-pagination :deep(.el-input__wrapper) {
  min-height: 30px;
  border-radius: 6px;
}

.inline-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.inline-actions :deep(.el-button) {
  height: 24px;
  padding: 0;
  margin: 0;
  font-size: 13px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.section-title h3,
.sync-summary h3 {
  margin: 0;
  color: #17233d;
  font-size: 15px;
}

.section-title span {
  color: #667085;
  font-size: 13px;
}

.config-form {
  padding: 16px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  background: #fbfcff;
}

.federal-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;
  padding: 12px;
}

.federal-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.federal-form :deep(.el-form-item__label) {
  margin-bottom: 5px;
  color: #344054;
  font-size: 13px;
  font-weight: 600;
}

.federal-form :deep(.el-select__wrapper),
.federal-form :deep(.el-input__wrapper) {
  min-height: 34px;
  border-radius: 6px;
}

.federal-form .button-row {
  grid-column: 1 / -1;
  padding-top: 2px;
}

.federal-form .button-row :deep(.el-button) {
  height: 34px;
  min-width: 92px;
  margin: 0;
  border-radius: 4px;
}

.tab-alert {
  margin: 4px 0 14px;
}

.button-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.federal-form .button-row {
  flex-wrap: nowrap;
}

.neo4j-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.neo4j-form :deep(.el-form-item:nth-child(5)),
.neo4j-form .button-row {
  grid-column: 1 / -1;
}

.sync-summary {
  min-height: 0;
  padding: 14px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  background: #fbfcff;
}

.sync-summary h3 {
  margin-bottom: 12px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.sync-stat {
  min-height: 62px;
  padding: 12px;
  border: 1px solid #edf1f7;
  border-radius: 6px;
  background: #fff;
}

.sync-stat span {
  display: block;
  margin-bottom: 8px;
  color: #667085;
  font-size: 12px;
  line-height: 1;
}

.sync-stat strong {
  display: flex;
  align-items: center;
  min-width: 0;
  color: #17233d;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.2;
  word-break: break-word;
}

.sync-stat:first-child {
  grid-column: 1 / -1;
}

.sync-stat:first-child strong {
  font-size: 14px;
}

.full-width {
  width: 100%;
}

.federal-preview {
  margin-bottom: 12px;
}

.federal-preview pre {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
}

.drawer-form {
  margin-top: 22px;
}

.drawer-final-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 1280px) {
  .datasource-heading,
  .main-grid,
  .schema-split,
  .form-table-grid,
  .neo4j-layout {
    grid-template-columns: 1fr;
  }

  .datasource-heading {
    display: grid;
  }

  .heading-actions {
    justify-content: flex-start;
    min-width: 0;
  }
}

@media (max-width: 860px) {
  .overview-grid,
  .detail-columns,
  .table-toolbar,
  .neo4j-form {
    grid-template-columns: 1fr;
  }
}
</style>
