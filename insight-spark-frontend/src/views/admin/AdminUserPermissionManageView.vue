<template>
  <section class="aup-page">
    <header class="hero-card">
      <div class="hero-copy">
        <h1>用户与权限管理</h1>
        <div class="hero-actions">
          <el-button type="primary" @click="openUser()">新增用户</el-button>
          <el-button @click="activeTab = 'grants'">去授权数据</el-button>
          <el-button :loading="loading" :icon="Refresh" @click="loadAll">刷新</el-button>
        </div>
      </div>
      <div class="hero-guide">
        <button
          v-for="step in workflowSteps"
          :key="step.no"
          type="button"
          class="guide-step"
          :class="{ active: activeTab === step.tab }"
          @click="activeTab = step.tab"
        >
          <span class="guide-step-no">{{ step.no }}</span>
          <div class="guide-step-text">
            <strong>{{ step.title }}</strong>
            <small>{{ step.desc }}</small>
          </div>
        </button>
      </div>
    </header>

    <div class="metric-grid">
      <article
        v-for="item in metrics"
        :key="item.label"
        class="metric-card"
        :class="`metric-card--${item.tone}`"
      >
        <div class="metric-card-head">
          <span class="metric-dot" :class="`is-${item.tone}`"></span>
          <span class="metric-label">{{ item.label }}</span>
        </div>
        <strong class="metric-value">{{ item.value }}</strong>
        <small class="metric-note">{{ item.note }}</small>
      </article>
    </div>

    <el-alert class="starter-alert" type="info" :closable="false" show-icon>
      <template #title>不知道从哪里开始？新用户先点“新增用户”，保存后在用户列表点“绑定角色”，最后到“数据授权/回收”为他选择数据资源，再进入“权限预览与校验”确认结果。</template>
    </el-alert>

    <el-tabs v-model="activeTab" class="main-tabs">
      <el-tab-pane label="用户管理" name="users">
        <section class="panel">
          <div class="panel-head">
            <div>
              <span class="section-kicker">STEP 1</span>
              <h2>用户管理</h2>
              <p>创建账号、维护状态，并给用户绑定一个或多个角色。新用户必须先有账号和角色，后续授权才有对象可选。</p>
            </div>
            <div class="tools"><el-input v-model="userKeyword" placeholder="搜索用户ID / 昵称 / 邮箱" clearable @keyup.enter="loadUsers" /><el-button @click="loadUsers">搜索</el-button><el-button type="primary" @click="openUser()">新增用户</el-button></div>
          </div>
          <el-empty v-if="!users.length && !loading" description="还没有用户。先创建一个用户，再绑定角色和授权数据。"><el-button type="primary" @click="openUser()">创建第一个用户</el-button></el-empty>
          <el-table v-else :data="users" height="430" stripe class="pretty-table">
            <el-table-column prop="userId" label="用户ID" width="130" />
            <el-table-column prop="username" label="用户名" width="120" />
            <el-table-column prop="nickname" label="昵称" width="120" />
            <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
            <el-table-column label="角色" min-width="190"><template #default="{ row }"><el-tag v-for="r in row.roles" :key="r" class="tag" effect="light">{{ r }}</el-tag><el-tag v-if="!row.roles?.length" type="warning">未绑定</el-tag></template></el-table-column>
            <el-table-column label="状态" width="96"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
            <el-table-column label="下一步/操作" width="360" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openUser(row)">编辑</el-button><el-button link type="primary" @click="openRoleBind(row)">绑定角色</el-button><el-button link @click="prepareGrantForUser(row)">授权数据</el-button><el-button link @click="loadPreview(row.userId)">预览</el-button><el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggleUser(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="角色与权限" name="roles">
        <section class="panel panel--roles">
          <div class="panel-head panel-head--roles">
            <div>
              <span class="section-kicker">STEP 2</span>
              <h2>角色管理与可视化权限</h2>
              <p>左侧选择角色，右侧勾选菜单 / 操作 / 数据权限；「编辑」改基础信息，「配置权限」切换右侧面板。</p>
            </div>
            <el-button @click="openRole()">新增角色</el-button>
          </div>

          <div v-show="!permissionGuideCollapsed" class="grant-usage-guide">
            <div class="grant-usage-guide-head">
              <el-icon class="grant-usage-guide-icon"><InfoFilled /></el-icon>
              <strong>权限勾选使用说明</strong>
              <el-button link class="grant-usage-guide-close" @click="permissionGuideCollapsed = true">收起</el-button>
            </div>
            <ul class="grant-usage-list grant-usage-list--detail">
              <li>
                <strong>两种权限数：</strong>左侧「拥有权限」是本角色直接勾选的数量；「有效权限」是合并继承链后的总数，点击数字可查看明细及来源角色。
              </li>
              <li>
                <strong>继承关系：</strong>子角色会自动继承父角色权限。右侧只需勾选本角色额外增加的项，不必重复勾选父角色已有权限。
              </li>
              <li>
                <strong>右侧统计：</strong>「有效 X · 本角色勾选 Y / Z」分别表示该角色最终生效总数、当前编辑中的勾选数、权限目录总数。
              </li>
              <li>
                <strong>操作按钮：</strong>「全选 / 清空 / 重置」仅影响右侧勾选；有修改后需点「保存角色权限」。已登录用户需刷新页面或重新登录后菜单才会更新。
              </li>
              <li>
                <strong>超级管理员：</strong>勾选「超级管理员」后会自动全选其余权限；运行时亦会放行全部菜单与数据，并继承管理员、普通用户的权限。
              </li>
              <li>
                <strong>与数据授权区别：</strong>此处配置菜单 / 操作 / 数据类功能权限；单表、看板等具体资源访问请前往
                <el-button link type="primary" @click="activeTab = 'grants'">数据授权 / 回收</el-button>
                Tab。
              </li>
            </ul>
          </div>
          <el-button
            v-if="permissionGuideCollapsed"
            link
            type="primary"
            class="grant-guide-toggle"
            @click="permissionGuideCollapsed = false"
          >
            展开权限勾选说明
          </el-button>

          <div class="role-split">
            <div class="role-table-pane">
              <div class="role-pane-label">角色列表</div>
              <el-table
                ref="roleTableRef"
                :data="roles"
                row-key="roleCode"
                height="420"
                size="small"
                class="role-table pretty-table"
                highlight-current-row
                :current-row-key="selectedRole?.roleCode"
                @row-click="(row) => selectRole(row)"
              >
                <el-table-column label="角色" min-width="96" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div class="role-cell-name">
                      <strong>{{ row.roleName }}</strong>
                      <small>{{ row.roleCode }}</small>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="继承" width="80" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="role-inherit-text">{{ inheritanceLabel(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="拥有权限" width="72" align="center">
                  <template #default="{ row }">
                    <span class="role-direct-count">{{ directPermissionCount(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="有效权限" width="72" align="center">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      class="role-count-btn"
                      @click.stop="openPermissionDetail(row)"
                    >
                      {{ effectivePermissionCount(row) }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="72" align="center">
                  <template #default="{ row }">
                    <el-tooltip content="编辑角色信息" placement="top">
                      <el-button link type="primary" class="role-action-btn role-action-btn--icon" @click.stop="openRole(row)">
                        <el-icon><EditPen /></el-icon>
                      </el-button>
                    </el-tooltip>
                    <el-tooltip content="配置权限" placement="top">
                      <el-button
                        link
                        type="primary"
                        class="role-action-btn role-action-btn--icon"
                        :class="{ 'is-active': selectedRole?.roleCode === row.roleCode }"
                        @click.stop="configureRole(row)"
                      >
                        <el-icon><Lock /></el-icon>
                      </el-button>
                    </el-tooltip>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div
              class="permission-editor"
              :class="{ 'is-pulse': rolePanelPulse, 'is-linked': !!selectedRole }"
              v-loading="rolePanelLoading"
            >
              <div class="editor-title">
                <div>
                  <span v-if="selectedRole">已选中：{{ selectedRole.roleName }}</span>
                  <span v-else>权限配置</span>
                  <h3>{{ selectedRole ? '配置菜单 / 操作 / 数据权限' : '请先在左侧选择角色' }}</h3>
                </div>
                <el-tag v-if="selectedRole" size="small" effect="plain" type="primary">{{ selectedRole.roleCode }}</el-tag>
              </div>

              <el-empty v-if="!selectedRole" description="点击左侧角色行或「配置权限」开始" :image-size="72" />

              <template v-else>
                <div class="permission-toolbar">
                  <el-button size="small" @click="selectAllRolePermissions">全选</el-button>
                  <el-button size="small" @click="clearRolePermissions">清空</el-button>
                  <el-button size="small" :disabled="!permissionDirty" @click="resetRolePermissions">重置</el-button>
                  <span class="permission-toolbar-meta">
                    有效 {{ effectivePermissionCount(selectedRole) }} · 本角色勾选 {{ selectedPermissionCodes.length }} / {{ allPermissionCodes.length }}
                  </span>
                </div>

                <el-checkbox-group v-model="selectedPermissionCodes" class="permission-groups">
                  <div v-for="group in permissionGroups" :key="group.key" class="permission-group">
                    <div class="permission-group-title">{{ group.title }}</div>
                    <div class="permission-group-items">
                      <el-checkbox
                        v-for="p in group.items"
                        :key="p.permissionCode"
                        :label="p.permissionCode"
                      >
                        {{ p.permissionName || p.permissionCode }}
                      </el-checkbox>
                    </div>
                  </div>
                </el-checkbox-group>

                <div class="permission-editor-footer">
                  <span v-if="permissionDirty" class="permission-dirty-hint">有未保存的修改</span>
                  <el-button
                    type="primary"
                    :disabled="!permissionDirty"
                    :loading="rolePermissionSaving"
                    @click="saveRolePermissionSelection"
                  >
                    保存角色权限
                  </el-button>
                </div>
              </template>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="数据授权/回收" name="grants">
        <section class="panel panel--grants">
          <div class="panel-head panel-head--grants">
            <div>
              <span class="section-kicker">STEP 3</span>
              <h2>管理员主动授权 / 回收权限</h2>
              <p>选择授权对象与资源后批量授权或回收，支持多资源一次性操作。</p>
            </div>
            <el-button @click="refreshGrantPanel">
              <el-icon><Refresh /></el-icon>
              刷新授权
            </el-button>
          </div>

          <div v-show="!grantGuideCollapsed" class="grant-usage-guide">
            <div class="grant-usage-guide-head">
              <el-icon class="grant-usage-guide-icon"><InfoFilled /></el-icon>
              <strong>授权操作说明</strong>
              <el-button link class="grant-usage-guide-close" @click="grantGuideCollapsed = true">收起</el-button>
            </div>
            <ol class="grant-usage-list">
              <li>选择用户或角色作为授权对象</li>
              <li>选择上传表、官方库或看板资源，可一次勾选多个；三种类型互不相同，看板需在「看板」类型下选择</li>
              <li>选择只读或编辑权限，有效期留空表示长期有效</li>
              <li>点击批量授权或批量回收，完成后可在「权限预览与校验」确认结果</li>
            </ol>
          </div>
          <el-button
            v-if="grantGuideCollapsed"
            link
            type="primary"
            class="grant-guide-toggle"
            @click="grantGuideCollapsed = false"
          >
            展开授权操作说明
          </el-button>

          <div class="grant-form-card">
            <div class="grant-form-card-head">
              <span class="grant-form-card-title">新建授权</span>
              <span class="grant-form-card-meta">填写后点击批量授权或回收</span>
            </div>
            <el-form :model="grantForm" label-position="top" class="grant-form">
              <div class="grant-form-grid">
                <el-form-item label="授权对象">
                  <el-segmented v-model="grantForm.targetType" :options="grantTargetTypeOptions" block class="grant-segmented" />
                </el-form-item>
                <el-form-item label="选择对象" class="grant-form-item--span-2">
                  <el-select v-model="grantForm.targetId" filterable placeholder="请选择用户或角色">
                    <el-option v-for="item in grantTargets" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                </el-form-item>
                <el-form-item label="资源类型" class="grant-form-item--full">
                  <el-segmented v-model="grantForm.scope" :options="grantScopeOptions" block class="grant-segmented" />
                  <p class="grant-scope-hint">{{ grantScopeHint }}</p>
                </el-form-item>
                <el-form-item class="grant-form-item--span-2">
                  <template #label>
                    选择资源
                    <span class="grant-resource-count">（{{ currentResources.length }} 个可选）</span>
                  </template>
                  <el-select
                    v-model="grantForm.resources"
                    filterable
                    multiple
                    collapse-tags
                    collapse-tags-tooltip
                    :placeholder="currentResourcePlaceholder"
                    :no-data-text="currentResourceEmptyHint"
                  >
                    <el-option
                      v-for="item in currentResources"
                      :key="item.tableName"
                      :label="item.displayName || item.tableName"
                      :value="item.tableName"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="权限">
                  <el-segmented v-model="grantForm.permissionType" :options="grantPermissionTypeOptions" block class="grant-segmented" />
                </el-form-item>
                <el-form-item label="有效期">
                  <el-date-picker v-model="grantForm.expireAt" value-format="YYYY-MM-DD" placeholder="为空表示长期" />
                </el-form-item>
              </div>
              <div class="grant-form-actions">
                <el-button type="primary" :disabled="!canSubmitGrant" @click="batchGrant">批量授权</el-button>
                <el-button type="danger" plain :disabled="!canSubmitGrant" @click="batchRevoke">批量回收</el-button>
                <el-button :disabled="!grantForm.targetId || grantForm.targetType !== 'USER'" @click="loadPreview(grantForm.targetId)">预览该用户</el-button>
              </div>
            </el-form>
          </div>

          <div class="grant-records">
            <div class="grant-records-head">
              <span class="grant-records-title">授权记录</span>
              <span class="grant-records-meta">共 {{ grants.length }} 条</span>
            </div>
            <el-table
              :data="grants"
              stripe
              class="pretty-table grants-table"
              max-height="520"
              empty-text="暂无授权记录，请在上方完成授权操作"
            >
              <el-table-column label="类型" width="96">
                <template #default="{ row }">{{ grantScopeLabel(row.scope) }}</template>
              </el-table-column>
              <el-table-column prop="targetId" label="对象" width="140" show-overflow-tooltip />
              <el-table-column label="资源" min-width="260">
                <template #default="{ row }">
                  <div class="grant-resource-cell">
                    <span class="grant-resource-name">{{ row.displayName || row.resource }}</span>
                    <span class="grant-resource-meta">
                      <template v-if="row.displayName && row.displayName !== row.resource">{{ row.resource }}</template>
                      <template v-if="row.ownerId"> · 归属 {{ row.ownerId }}</template>
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="权限" width="88" align="center">
                <template #default="{ row }">{{ grantPermissionLabel(row.permissionType) }}</template>
              </el-table-column>
              <el-table-column label="授权来源" width="108" align="center">
                <template #default="{ row }">
                  <el-tag size="small" effect="light" :type="grantSourceTagType(row)">
                    {{ grantSourceLabel(row) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="有效期" width="120">
                <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="88" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button link type="danger" @click="revokeSingle(row)">回收</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="权限预览与校验" name="preview">
        <section class="panel panel--preview">
          <div class="panel-head panel-head--preview">
            <div>
              <span class="section-kicker">STEP 4</span>
              <h2>权限预览与校验</h2>
              <p>以管理员视角核对用户最终生效的角色链、功能权限与可访问资源。</p>
            </div>
            <div class="preview-head-tools">
              <el-select
                v-model="previewUserId"
                filterable
                clearable
                placeholder="选择用户进行校验"
                class="preview-user-select"
                @change="loadPreview"
              >
                <el-option
                  v-for="u in users"
                  :key="u.userId"
                  :label="`${u.nickname || u.username}（${u.userId}）`"
                  :value="u.userId"
                />
              </el-select>
              <el-button :disabled="!previewUserId" @click="loadPreview(previewUserId)">
                <el-icon><Refresh /></el-icon>
                刷新
              </el-button>
            </div>
          </div>

          <div v-show="!previewGuideCollapsed" class="grant-usage-guide">
            <div class="grant-usage-guide-head">
              <el-icon class="grant-usage-guide-icon"><InfoFilled /></el-icon>
              <strong>预览校验说明</strong>
              <el-button link class="grant-usage-guide-close" @click="previewGuideCollapsed = true">收起</el-button>
            </div>
            <ol class="grant-usage-list">
              <li>选择用户后，系统汇总其角色继承链、有效权限与数据授权结果</li>
              <li>「有效权限」为角色链合并后的菜单 / 操作 / 数据能力，「授权资源」为单独授予的表 / 库 / 看板记录（含授权来源）</li>
              <li>「可访问数据」展示该用户当前实际能访问的上传表与官方库资源</li>
              <li>若结果与预期不符，请回到「角色与权限」或「数据授权 / 回收」调整后再次预览</li>
            </ol>
          </div>
          <el-button
            v-if="previewGuideCollapsed"
            link
            type="primary"
            class="grant-guide-toggle"
            @click="previewGuideCollapsed = false"
          >
            展开预览校验说明
          </el-button>

          <template v-if="preview.user">
            <div class="preview-summary">
              <div class="preview-user-card">
                <div class="preview-user-avatar">{{ previewUserInitial }}</div>
                <div class="preview-user-meta">
                  <div class="preview-user-title">
                    <strong>{{ preview.user.nickname || preview.user.username }}</strong>
                    <el-tag size="small" :type="preview.user.status === 'ACTIVE' ? 'success' : 'info'" effect="light">
                      {{ previewStatusLabel(preview.user.status) }}
                    </el-tag>
                  </div>
                  <span class="preview-user-sub">{{ preview.user.userId }} · {{ preview.user.username }}</span>
                </div>
              </div>

              <div class="preview-metric-row">
                <article class="preview-metric">
                  <span>有效权限</span>
                  <strong>{{ preview.permissions?.length || 0 }}</strong>
                  <small>菜单 / 操作 / 数据</small>
                </article>
                <article class="preview-metric">
                  <span>授权资源</span>
                  <strong>{{ preview.dataGrants?.length || 0 }}</strong>
                  <small>表 / 官方库 / 看板</small>
                </article>
                <article class="preview-metric">
                  <span>可访问数据</span>
                  <strong>{{ preview.accessibleTables?.length || 0 }}</strong>
                  <small>上传表 + 官方库</small>
                </article>
              </div>
            </div>

            <div class="preview-role-chain">
              <span class="preview-role-chain-label">角色链</span>
              <div v-if="preview.effectiveRoles?.length" class="preview-role-chain-tags">
                <template v-for="(code, index) in preview.effectiveRoles" :key="code">
                  <el-tag size="small" effect="plain" type="primary">{{ previewRoleLabel(code) }}</el-tag>
                  <span v-if="index < preview.effectiveRoles.length - 1" class="preview-role-arrow">→</span>
                </template>
              </div>
              <span v-else class="preview-role-empty">暂无绑定角色</span>
            </div>

            <div class="preview-detail-card">
              <el-tabs class="preview-tabs">
                <el-tab-pane :label="`有效权限（${preview.permissions?.length || 0}）`">
                  <el-table
                    :data="preview.permissions"
                    size="small"
                    stripe
                    max-height="440"
                    class="pretty-table preview-table"
                    empty-text="该用户暂无有效权限"
                  >
                    <el-table-column label="类型" width="88" align="center">
                      <template #default="{ row }">
                        <el-tag size="small" effect="light" :type="previewPermissionTagType(row.permissionType)">
                          {{ previewPermissionTypeLabel(row.permissionType) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="permissionName" label="权限名称" min-width="220" show-overflow-tooltip />
                  </el-table>
                </el-tab-pane>
                <el-tab-pane :label="`授权资源（${preview.dataGrants?.length || 0}）`">
                  <el-table
                    :data="preview.dataGrants"
                    size="small"
                    stripe
                    max-height="440"
                    class="pretty-table preview-table"
                    empty-text="暂无单独授权的资源"
                  >
                    <el-table-column label="类型" width="96">
                      <template #default="{ row }">{{ grantScopeLabel(row.scope) }}</template>
                    </el-table-column>
                    <el-table-column label="资源" min-width="260">
                      <template #default="{ row }">
                        <div class="grant-resource-cell">
                          <span class="grant-resource-name">{{ row.displayName || row.resource }}</span>
                          <span class="grant-resource-meta">
                            <template v-if="row.displayName && row.displayName !== row.resource">{{ row.resource }}</template>
                            <template v-if="row.ownerId"> · 归属 {{ row.ownerId }}</template>
                          </span>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column label="权限" width="88" align="center">
                      <template #default="{ row }">{{ grantPermissionLabel(row.permissionType) }}</template>
                    </el-table-column>
                    <el-table-column label="授权来源" width="108" align="center">
                      <template #default="{ row }">
                        <el-tag size="small" effect="light" :type="grantSourceTagType(row)">
                          {{ grantSourceLabel(row) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column label="有效期" width="120">
                      <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
                    </el-table-column>
                  </el-table>
                </el-tab-pane>
                <el-tab-pane :label="`可访问数据（${preview.accessibleTables?.length || 0}）`">
                  <el-table
                    :data="preview.accessibleTables"
                    size="small"
                    stripe
                    max-height="440"
                    class="pretty-table preview-table"
                    empty-text="暂无可访问的数据资源"
                  >
                    <el-table-column label="来源" width="96">
                      <template #default="{ row }">{{ previewSourceTypeLabel(row.sourceType) }}</template>
                    </el-table-column>
                    <el-table-column prop="displayName" label="名称" min-width="220" show-overflow-tooltip />
                    <el-table-column prop="ownerId" label="归属" width="140" show-overflow-tooltip />
                  </el-table>
                </el-tab-pane>
              </el-tabs>
            </div>
          </template>

          <div v-else class="preview-empty">
            <el-empty description="请选择用户，查看其最终生效的权限与资源">
              <el-button type="primary" @click="activeTab = 'users'">去选择用户</el-button>
            </el-empty>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="userDialog" title="用户信息" width="620px"><el-form :model="userForm" label-position="top" class="dialog-grid"><el-form-item label="用户ID"><el-input v-model="userForm.userId" :disabled="!!editingUser" placeholder="如 user_001" /></el-form-item><el-form-item label="用户名"><el-input v-model="userForm.username" placeholder="登录名" /></el-form-item><el-form-item label="昵称"><el-input v-model="userForm.nickname" placeholder="页面显示名称" /></el-form-item><el-form-item label="邮箱"><el-input v-model="userForm.email" /></el-form-item><el-form-item label="手机号"><el-input v-model="userForm.phone" /></el-form-item><el-form-item label="初始角色"><el-select v-model="userForm.role"><el-option v-for="r in roles" :key="r.roleCode" :label="`${r.roleName}（${r.roleCode}）`" :value="r.roleCode" /></el-select></el-form-item><el-form-item label="状态"><el-segmented v-model="userForm.status" :options="['ACTIVE', 'DISABLED']" /></el-form-item><el-form-item label="密码"><el-input v-model="userForm.password" show-password placeholder="新增必填，编辑留空不修改" /></el-form-item></el-form><template #footer><el-button @click="userDialog = false">取消</el-button><el-button type="primary" @click="saveUser">保存用户</el-button></template></el-dialog>
    <el-dialog v-model="roleDialog" title="角色信息" width="560px"><el-form :model="roleForm" label-position="top"><el-form-item label="角色编码"><el-input v-model="roleForm.roleCode" :disabled="!!editingRole" placeholder="如 DATA_ANALYST" /></el-form-item><el-form-item label="角色名称"><el-input v-model="roleForm.roleName" /></el-form-item><el-form-item label="继承自"><el-select v-model="roleForm.parentRoleCode" clearable><el-option v-for="r in roles" :key="r.roleCode" :label="r.roleName" :value="r.roleCode" /></el-select></el-form-item><el-form-item label="描述"><el-input v-model="roleForm.description" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="roleDialog = false">取消</el-button><el-button type="primary" @click="saveRole">保存角色</el-button></template></el-dialog>
    <el-dialog v-model="roleBindDialog" title="绑定用户角色" width="500px"><el-alert type="info" :closable="false" show-icon title="一个用户可绑定多个角色，最终权限会合并并继承父角色。" /><el-checkbox-group v-model="roleBindCodes" class="role-bind-list"><el-checkbox v-for="r in roles" :key="r.roleCode" :label="r.roleCode">{{ r.roleName }}（{{ r.roleCode }}）</el-checkbox></el-checkbox-group><template #footer><el-button @click="roleBindDialog = false">取消</el-button><el-button type="primary" @click="saveRoleBind">保存绑定</el-button></template></el-dialog>

    <el-dialog
      v-model="permissionDetailVisible"
      :title="`权限明细 · ${permissionDetailRole?.roleName || ''}`"
      width="520px"
    >
      <el-empty v-if="!permissionDetailItems.length" description="该角色暂无有效权限" />
      <el-table v-else :data="permissionDetailItems" size="small" max-height="360" class="pretty-table">
        <el-table-column prop="name" label="权限名称" min-width="160" />
        <el-table-column prop="code" label="编码" min-width="130" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="80" />
        <el-table-column prop="sourceRole" label="来源角色" width="100" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { EditPen, InfoFilled, Lock, Refresh } from '@element-plus/icons-vue'
import { refreshSessionProfile } from '../../store/session'
import { SUPER_ADMIN_PERMISSION } from '../../router/modules'
import {
  bindAdminUserRoles,
  fetchAdminDataGrants,
  fetchAdminPermissionCatalog,
  fetchAdminPermissionPreview,
  fetchAdminPermissionResources,
  fetchAdminRoles,
  fetchAdminUserPermissionOverview,
  fetchAdminUsers,
  grantAdminDataPermission,
  revokeAdminDataPermission,
  saveAdminRole,
  saveAdminRolePermissions,
  saveAdminUser,
  updateAdminUserStatus
} from '../../api/adminUserPermission'

const loading = ref(false)
const activeTab = ref('users')
const overview = ref({})
const users = ref([])
const roles = ref([])
const grants = ref([])
const preview = ref({})
const catalog = reactive({ menu: [], operation: [], data: [] })
const resources = reactive({ uploadTables: [], officialTables: [], dashboards: [] })
const userKeyword = ref('')
const previewUserId = ref('')
const selectedRole = ref(null)
const selectedPermissionCodes = ref([])
const rolePermissionSnapshot = ref([])
const roleTableRef = ref(null)
const rolePanelLoading = ref(false)
const rolePanelPulse = ref(false)
const rolePermissionSaving = ref(false)
const permissionGuideCollapsed = ref(true)
const grantGuideCollapsed = ref(true)
const previewGuideCollapsed = ref(true)
const permissionDetailVisible = ref(false)
const permissionDetailRole = ref(null)
const skipSuperAdminAutoSelect = ref(false)
const userDialog = ref(false)
const roleDialog = ref(false)
const roleBindDialog = ref(false)
const editingUser = ref(null)
const editingRole = ref(null)
const bindingUser = ref(null)
const roleBindCodes = ref([])

const userForm = reactive({ userId: '', username: '', nickname: '', phone: '', email: '', role: 'USER', status: 'ACTIVE', password: '' })
const roleForm = reactive({ roleCode: '', roleName: '', parentRoleCode: '', roleLevel: 1, dataScope: 'SELF', description: '', enabled: true })
const grantForm = reactive({ targetType: 'USER', targetId: '', scope: 'TABLE', resources: [], permissionType: 'READ', expireAt: '' })
const grantTargetTypeOptions = [
  { label: '用户', value: 'USER' },
  { label: '角色', value: 'ROLE' }
]
const grantScopeOptions = [
  { label: '上传表', value: 'TABLE' },
  { label: '官方库', value: 'OFFICIAL' },
  { label: '看板', value: 'DASHBOARD' }
]
const grantPermissionTypeOptions = [
  { label: '只读', value: 'READ' },
  { label: '编辑', value: 'EDIT' }
]

const workflowSteps = [
  { no: '01', title: '建用户', desc: '账号与状态', tab: 'users' },
  { no: '02', title: '配角色', desc: '菜单/操作/数据能力', tab: 'roles' },
  { no: '03', title: '授权数据', desc: '表/官方库/看板', tab: 'grants' },
  { no: '04', title: '预览校验', desc: '确认最终权限', tab: 'preview' }
]

const metrics = computed(() => [
  { label: '用户数', value: overview.value.userCount ?? 0, note: `${overview.value.activeUserCount ?? 0} 个启用`, tone: 'blue' },
  { label: '角色数', value: overview.value.roleCount ?? 0, note: `${overview.value.bindingCount ?? 0} 条绑定`, tone: 'green' },
  { label: '上传表授权', value: overview.value.dataGrantCount ?? 0, note: '用户级数据授权', tone: 'purple' },
  { label: '官方库授权', value: overview.value.officialGrantCount ?? 0, note: '官方库表级权限', tone: 'orange' },
  { label: '看板授权', value: overview.value.dashboardGrantCount ?? 0, note: '公共看板权限', tone: 'red' }
])
const permissionGroups = computed(() => [
  { key: 'menu', title: '菜单权限', items: catalog.menu || [] },
  { key: 'operation', title: '操作权限', items: catalog.operation || [] },
  { key: 'data', title: '数据权限', items: catalog.data || [] }
])
const permissionMap = computed(() => Object.fromEntries(permissionGroups.value.flatMap(g => g.items.map(p => [p.permissionCode, p]))))
const allPermissionCodes = computed(() => permissionGroups.value.flatMap(g => g.items.map(p => p.permissionCode)))
const permissionDirty = computed(() => {
  const current = [...selectedPermissionCodes.value].sort().join('|')
  const snapshot = [...rolePermissionSnapshot.value].sort().join('|')
  return current !== snapshot
})
const permissionDetailItems = computed(() => {
  const row = permissionDetailRole.value
  if (!row) return []
  return effectivePermissionItems(row)
})
const grantTargets = computed(() => grantForm.targetType === 'USER'
  ? users.value.map(u => ({ label: `${u.nickname || u.username}（${u.userId}）`, value: u.userId }))
  : roles.value.map(r => ({ label: `${r.roleName}（${r.roleCode}）`, value: r.roleCode })))
const currentResources = computed(() => grantForm.scope === 'OFFICIAL' ? resources.officialTables : grantForm.scope === 'DASHBOARD' ? resources.dashboards : resources.uploadTables)
const grantScopeHint = computed(() => {
  if (grantForm.scope === 'DASHBOARD') {
    return '看板列表来自「看板管理」，与「上传表」无关；请选择要授权给他人的看板。'
  }
  if (grantForm.scope === 'OFFICIAL') {
    return '官方库表来自「数据源管理」中已启用且已同步的库表。'
  }
  return '上传表来自用户「数据上传」注册的表；若为空表示平台尚无上传数据。'
})
const currentResourcePlaceholder = computed(() => {
  if (currentResources.value.length) return '可批量选择多个资源'
  return '暂无可选资源，请切换资源类型或先创建对应资源'
})
const currentResourceEmptyHint = computed(() => {
  if (grantForm.scope === 'DASHBOARD') {
    return '暂无看板，请先在「看板管理」中创建'
  }
  if (grantForm.scope === 'OFFICIAL') {
    return '暂无官方库表，请先在「数据源管理」中配置并同步'
  }
  return '暂无上传表，请先在用户端完成数据上传'
})
const canSubmitGrant = computed(() => grantForm.targetId && grantForm.resources.length && grantForm.permissionType)
const previewUserInitial = computed(() => {
  const name = preview.value?.user?.nickname || preview.value?.user?.username || '?'
  return name.slice(0, 1).toUpperCase()
})

watch(() => grantForm.targetType, () => { grantForm.targetId = '' })
watch(() => grantForm.scope, () => { grantForm.resources = [] })

watch(activeTab, (tab) => {
  if (tab === 'grants') loadResources()
})

watch(selectedPermissionCodes, (codes, prev) => {
  if (skipSuperAdminAutoSelect.value) return
  const previous = prev || []
  const justEnabledSuperAdmin = codes.includes(SUPER_ADMIN_PERMISSION) && !previous.includes(SUPER_ADMIN_PERMISSION)
  if (justEnabledSuperAdmin && codes.length < allPermissionCodes.value.length) {
    skipSuperAdminAutoSelect.value = true
    selectedPermissionCodes.value = [...allPermissionCodes.value]
    nextTick(() => { skipSuperAdminAutoSelect.value = false })
  }
})

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadUsers(), loadRoles(), loadCatalog(), loadResources(), loadGrants()])
  } finally {
    loading.value = false
  }
}
const loadOverview = async () => { overview.value = await fetchAdminUserPermissionOverview() }
const loadUsers = async () => { users.value = await fetchAdminUsers(userKeyword.value) }
const loadRoles = async () => { roles.value = await fetchAdminRoles() }
const loadCatalog = async () => { Object.assign(catalog, await fetchAdminPermissionCatalog()) }
const loadResources = async () => {
  try {
    const data = await fetchAdminPermissionResources()
    resources.uploadTables = data?.uploadTables || []
    resources.officialTables = data?.officialTables || []
    resources.dashboards = data?.dashboards || []
  } catch (e) {
    ElMessage.error(e?.message || '资源列表加载失败，请刷新页面重试')
  }
}
const loadGrants = async () => { grants.value = await fetchAdminDataGrants() }
const refreshGrantPanel = async () => {
  await Promise.all([loadResources(), loadGrants()])
}

const openUser = (row) => {
  editingUser.value = row || null
  Object.assign(userForm, row ? { ...row, role: row.role || row.roles?.[0] || 'USER', password: '' } : { userId: '', username: '', nickname: '', phone: '', email: '', role: 'USER', status: 'ACTIVE', password: '' })
  userDialog.value = true
}
const saveUser = async () => { await saveAdminUser({ ...userForm }); ElMessage.success('用户已保存，下一步可绑定角色或授权数据'); userDialog.value = false; await Promise.all([loadUsers(), loadOverview()]) }
const toggleUser = async (row) => { await updateAdminUserStatus(row.userId, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); ElMessage.success('状态已更新'); await loadUsers() }
const openRoleBind = (row) => { bindingUser.value = row; roleBindCodes.value = [...(row.roles || [])]; roleBindDialog.value = true }
const saveRoleBind = async () => { await bindAdminUserRoles(bindingUser.value.userId, roleBindCodes.value); ElMessage.success('角色绑定已保存，下一步可进行数据授权'); roleBindDialog.value = false; await loadUsers() }

const openRole = (row) => {
  editingRole.value = row || null
  Object.assign(roleForm, row ? { ...row, enabled: row.enabled !== false && row.enabled !== 0 } : { roleCode: '', roleName: '', parentRoleCode: '', roleLevel: 1, dataScope: 'SELF', description: '', enabled: true })
  roleDialog.value = true
}
const saveRole = async () => { await saveAdminRole({ ...roleForm }); ElMessage.success('角色已保存'); roleDialog.value = false; await Promise.all([loadRoles(), loadOverview()]) }

function inheritanceLabel(row) {
  if (!row?.parentRoleCode) return '无继承角色'
  const parent = roles.value.find((r) => r.roleCode === row.parentRoleCode)
  return parent ? `${parent.roleName}` : row.parentRoleCode
}

function effectivePermissionItems(row) {
  if (!row?.roleCode) return []
  const merged = new Map()
  const walk = (roleCode, visiting = new Set()) => {
    if (!roleCode || visiting.has(roleCode)) return
    visiting.add(roleCode)
    const role = roles.value.find((r) => r.roleCode === roleCode)
    if (!role) return
    for (const p of role.permissions || []) {
      if (!merged.has(p.permissionCode)) {
        merged.set(p.permissionCode, {
          code: p.permissionCode,
          name: p.permissionName || p.permissionCode,
          type: p.permissionType || '—',
          sourceRole: role.roleName
        })
      }
    }
    if (role.parentRoleCode) walk(role.parentRoleCode, visiting)
  }
  walk(row.roleCode)
  return [...merged.values()]
}

function effectivePermissionCount(row) {
  return effectivePermissionItems(row).length
}

function directPermissionCount(row) {
  if (row?.directPermissionCount != null) return row.directPermissionCount
  return row?.permissions?.length || 0
}

function grantScopeLabel(scope) {
  const map = { TABLE: '上传表', OFFICIAL: '官方库', DASHBOARD: '看板' }
  return map[scope] || scope || '—'
}

function grantPermissionLabel(type) {
  const map = { READ: '只读', EDIT: '编辑' }
  return map[type] || type || '—'
}

function grantSourceLabel(row) {
  if (row?.grantSourceLabel) return row.grantSourceLabel
  const map = {
    ADMIN: '管理员授权',
    GRANT: '管理员授权',
    REQUEST: '审批通过',
    COLLAB: '看板协作'
  }
  return map[row?.grantSource] || '管理员授权'
}

function grantSourceTagType(row) {
  const label = grantSourceLabel(row)
  if (label === '审批通过') return 'success'
  if (label === '看板协作') return 'warning'
  return 'primary'
}

function previewStatusLabel(status) {
  if (status === 'ACTIVE') return '启用'
  if (status === 'DISABLED') return '停用'
  return status || '—'
}

function previewRoleLabel(roleCode) {
  const code = roleCode || ''
  const role = roles.value.find((r) => r.roleCode === code)
  return role ? `${role.roleName}` : code || '—'
}

function previewPermissionTypeLabel(type) {
  const map = { MENU: '菜单', OPERATION: '操作', DATA: '数据' }
  return map[type] || type || '—'
}

function previewPermissionTagType(type) {
  const map = { MENU: 'primary', OPERATION: 'warning', DATA: 'success' }
  return map[type] || 'info'
}

function previewSourceTypeLabel(type) {
  const map = { UPLOAD: '上传表', OFFICIAL: '官方库', TABLE: '上传表' }
  return map[type] || type || '—'
}

function applyRoleSelection(row) {
  const loadedCodes = (row.permissions || []).map((p) => p.permissionCode)
  selectedRole.value = row
  skipSuperAdminAutoSelect.value = true
  rolePermissionSnapshot.value = [...loadedCodes]
  let displayCodes = [...loadedCodes]
  if (displayCodes.includes(SUPER_ADMIN_PERMISSION) && displayCodes.length < allPermissionCodes.value.length) {
    displayCodes = [...allPermissionCodes.value]
  }
  selectedPermissionCodes.value = displayCodes
  nextTick(() => {
    roleTableRef.value?.setCurrentRow?.(row)
    skipSuperAdminAutoSelect.value = false
  })
}

async function selectRole(row, options = {}) {
  if (!row) return
  rolePanelLoading.value = true
  await new Promise((resolve) => setTimeout(resolve, 120))
  applyRoleSelection(row)
  rolePanelLoading.value = false
  if (options.pulse) {
    rolePanelPulse.value = true
    setTimeout(() => { rolePanelPulse.value = false }, 650)
  }
}

function configureRole(row) {
  selectRole(row, { pulse: true })
}

function selectAllRolePermissions() {
  selectedPermissionCodes.value = [...allPermissionCodes.value]
}

function clearRolePermissions() {
  selectedPermissionCodes.value = []
}

function resetRolePermissions() {
  selectedPermissionCodes.value = [...rolePermissionSnapshot.value]
}

function openPermissionDetail(row) {
  permissionDetailRole.value = row
  permissionDetailVisible.value = true
}

const saveRolePermissionSelection = async () => {
  if (!selectedRole.value || !permissionDirty.value) return
  rolePermissionSaving.value = true
  try {
    const permissions = selectedPermissionCodes.value.map((code) => permissionMap.value[code]).filter(Boolean)
    await saveAdminRolePermissions(selectedRole.value.roleCode, permissions)
    ElMessage.success('角色权限已保存；已登录用户需刷新页面或重新登录后菜单才会更新')
    await refreshSessionProfile().catch(() => {})
    const savedCode = selectedRole.value.roleCode
    await Promise.all([loadRoles(), loadCatalog()])
    const refreshed = roles.value.find((r) => r.roleCode === savedCode)
    if (refreshed) applyRoleSelection(refreshed)
  } catch (e) {
    ElMessage.error(e?.message || '保存失败，请重试')
  } finally {
    rolePermissionSaving.value = false
  }
}

const prepareGrantForUser = (row) => {
  activeTab.value = 'grants'
  grantForm.targetType = 'USER'
  grantForm.targetId = row.userId
}
const batchGrant = async () => {
  for (const resource of grantForm.resources) await grantAdminDataPermission({ ...grantForm, resource })
  ElMessage.success('批量授权完成，建议进行权限预览校验')
  await Promise.all([loadGrants(), loadOverview()])
}
const batchRevoke = async () => {
  for (const resource of grantForm.resources) await revokeAdminDataPermission({ ...grantForm, resource })
  ElMessage.success('批量回收完成')
  await Promise.all([loadGrants(), loadOverview()])
}
const revokeSingle = async (row) => { await revokeAdminDataPermission({ scope: row.scope, targetType: 'USER', targetId: row.targetId, resource: row.resource, permissionType: row.permissionType }); ElMessage.success('已回收'); await Promise.all([loadGrants(), loadOverview()]) }
const loadPreview = async (userId) => {
  if (!userId) {
    preview.value = {}
    return
  }
  activeTab.value = 'preview'
  previewUserId.value = userId
  preview.value = await fetchAdminPermissionPreview(userId)
}

onMounted(loadAll)
</script>

<style scoped>
.aup-page { display: grid; gap: 16px; color: #13213a; }
.hero-card { position: relative; overflow: hidden; display: grid; grid-template-rows: auto auto; gap: 12px; padding: 16px 18px; border-radius: 16px; background: #fff; border: 1px solid #e8edf7; box-shadow: 0 6px 18px rgba(15, 23, 42, 0.04); }
.hero-copy h1 { margin: 0; color: #0f172a; font-size: 24px; letter-spacing: -.03em; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.hero-guide {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.guide-step {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(191,219,254,.9);
  border-radius: 12px;
  background: rgba(255,255,255,.72);
  cursor: pointer;
  text-align: left;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease, background .18s ease;
}
.guide-step:hover,
.guide-step.active {
  transform: translateY(-1px);
  border-color: #60a5fa;
  background: #fff;
  box-shadow: 0 8px 20px rgba(37,99,235,.1);
}
.guide-step-no {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
}
.guide-step-text {
  min-width: 0;
}
.guide-step strong,
.guide-step small {
  display: block;
}
.guide-step strong {
  font-size: 13px;
  color: #0f172a;
}
.guide-step small {
  color: #64748b;
  margin-top: 1px;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.metric-card {
  --metric-accent: #2563eb;
  position: relative;
  overflow: hidden;
  padding: 10px 12px 9px;
  border: 1px solid #e8edf7;
  border-radius: 12px;
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--metric-accent) 8%, transparent), transparent 55%),
    linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
  border-top: 3px solid var(--metric-accent);
}
.metric-card--blue { --metric-accent: #2563eb; }
.metric-card--green { --metric-accent: #10b981; }
.metric-card--purple { --metric-accent: #7c3aed; }
.metric-card--orange { --metric-accent: #f59e0b; }
.metric-card--red { --metric-accent: #ef4444; }
.metric-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.metric-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  margin: 0;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--metric-accent) 14%, transparent);
}
.metric-dot.is-blue { background: #2563eb; --metric-accent: #2563eb; }
.metric-dot.is-green { background: #10b981; --metric-accent: #10b981; }
.metric-dot.is-purple { background: #7c3aed; --metric-accent: #7c3aed; }
.metric-dot.is-orange { background: #f59e0b; --metric-accent: #f59e0b; }
.metric-dot.is-red { background: #ef4444; --metric-accent: #ef4444; }
.metric-label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.metric-value {
  display: block;
  margin: 5px 0 1px;
  color: #0f172a;
  font-size: 22px;
  line-height: 1.1;
  font-weight: 700;
}
.metric-note {
  display: block;
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.panel, .preview-card { border: 1px solid #e8edf7; border-radius: 24px; background: rgba(255,255,255,.94); box-shadow: 0 16px 42px rgba(15,23,42,.055); }
.panel--preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.panel-head--preview {
  margin-bottom: 0;
}
.preview-head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.preview-user-select {
  width: 280px;
}
.preview-summary {
  display: grid;
  grid-template-columns: minmax(260px, 1.1fr) minmax(0, 1.4fr);
  gap: 12px;
}
.preview-user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}
.preview-user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #2563eb;
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}
.preview-user-meta {
  min-width: 0;
}
.preview-user-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.preview-user-title strong {
  color: #0f172a;
  font-size: 16px;
}
.preview-user-sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  word-break: break-all;
}
.preview-metric-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.preview-metric {
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid #e8edf7;
  background: #fff;
  border-top: 3px solid #3b82f6;
}
.preview-metric span {
  display: block;
  font-size: 12px;
  color: #64748b;
}
.preview-metric strong {
  display: block;
  margin: 6px 0 2px;
  font-size: 24px;
  line-height: 1;
  color: #0f172a;
}
.preview-metric small {
  display: block;
  font-size: 11px;
  color: #94a3b8;
}
.preview-role-chain {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid #e8edf7;
  background: #f8fafc;
}
.preview-role-chain-label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  flex-shrink: 0;
}
.preview-role-chain-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.preview-role-arrow {
  color: #94a3b8;
  font-size: 12px;
}
.preview-role-empty {
  font-size: 12px;
  color: #94a3b8;
}
.preview-detail-card {
  padding: 4px 14px 14px;
  border-radius: 14px;
  border: 1px solid #e8edf7;
  background: #fff;
}
.preview-tabs :deep(.el-tabs__header) {
  margin-bottom: 10px;
}
.preview-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  font-weight: 600;
}
.preview-table {
  width: 100%;
}
.preview-empty {
  padding: 28px 0 12px;
  border-radius: 14px;
  border: 1px dashed #dbeafe;
  background: #f8fbff;
}
.starter-alert { border-radius: 16px; }
.main-tabs :deep(.el-tabs__header) { padding: 8px 12px 0; border-radius: 22px; background: #fff; box-shadow: 0 10px 28px rgba(15,23,42,.04); }
.panel { padding: 22px; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
.section-kicker { color: #2563eb; font-size: 12px; font-weight: 800; letter-spacing: .08em; }
.panel-head h2 { margin: 5px 0 6px; color: #0f172a; font-size: 21px; }
.panel-head p { max-width: 760px; margin: 0; color: #64748b; line-height: 1.7; }
.tools { display: flex; gap: 10px; }
.tools .el-input { width: 270px; }
.tag { margin-right: 5px; }
.pretty-table { border-radius: 16px; overflow: hidden; }
.role-split {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1.15fr);
  gap: 14px;
  align-items: stretch;
}
.role-table-pane {
  min-width: 0;
  padding: 12px 10px;
  border-radius: 14px;
  border: 1px solid #e8edf7;
  background: #f8fafc;
}
.role-table {
  width: 100%;
}
.role-table :deep(.el-table__inner-wrapper),
.role-table :deep(.el-table__body-wrapper),
.role-table :deep(.el-scrollbar__wrap) {
  overflow-x: hidden !important;
}
.role-table :deep(.el-table__header th.el-table__cell),
.role-table :deep(.el-table__body td.el-table__cell) {
  padding: 6px 0;
}
.role-table :deep(.el-table__header .cell),
.role-table :deep(.el-table__body .cell) {
  padding-left: 6px;
  padding-right: 6px;
}
.role-action-btn--icon {
  padding: 0 2px;
  margin: 0;
}
.role-action-btn--icon .el-icon {
  font-size: 15px;
}
.role-pane-label {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
}
.role-table :deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: #eff6ff !important;
}
.role-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: #f8fbff;
}
.role-cell-name strong {
  display: block;
  font-size: 13px;
  color: #0f172a;
}
.role-cell-name small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #94a3b8;
}
.role-inherit-text {
  font-size: 12px;
  color: #475569;
}
.role-direct-count {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}
.role-count-btn {
  font-weight: 700;
}
.role-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.role-action-btn.is-active {
  font-weight: 700;
  color: #1d4ed8 !important;
}
.panel-head--roles .el-button {
  border-color: #93c5fd;
  color: #2563eb;
  background: #eff6ff;
}
.panel--roles {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.permission-editor {
  display: flex;
  flex-direction: column;
  min-height: 420px;
  padding: 14px;
  border-radius: 14px;
  border: 1px solid #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
  transition: box-shadow 0.25s ease, border-color 0.25s ease;
}
.permission-editor.is-linked {
  border-color: #93c5fd;
  box-shadow: inset 3px 0 0 #3b82f6;
}
.permission-editor.is-pulse {
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.18), inset 3px 0 0 #3b82f6;
}
.editor-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.editor-title span {
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}
.editor-title h3 {
  margin: 4px 0 0;
  font-size: 16px;
  color: #0f172a;
}
.permission-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid #e8edf7;
}
.permission-toolbar-meta {
  margin-left: auto;
  font-size: 12px;
  color: #64748b;
}
.permission-groups {
  flex: 1;
  display: grid;
  gap: 10px;
  margin: 0;
  max-height: 300px;
  overflow: auto;
  padding-right: 4px;
}
.permission-group {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 10px 10px 8px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #edf2f7;
}
.permission-group-title {
  display: block;
  margin: 0 0 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid #eef2f7;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}
.permission-group-items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.permission-group-items :deep(.el-checkbox) {
  display: flex;
  align-items: flex-start;
  margin-right: 0;
  height: auto;
  line-height: 1.5;
}
.permission-group-items :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.5;
}
.permission-editor-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #e8edf7;
}
.permission-dirty-hint {
  margin-right: auto;
  font-size: 12px;
  color: #d97706;
}
.split { display: grid; grid-template-columns: minmax(0, 1fr) 460px; gap: 16px; }
.panel--grants {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.grant-usage-guide {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #dbeafe;
  background: #f0f7ff;
}
.grant-usage-guide-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 13px;
  color: #1e40af;
}
.grant-usage-guide-icon {
  font-size: 15px;
}
.grant-usage-guide-close {
  margin-left: auto;
  padding: 0;
}
.grant-usage-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: #475569;
  line-height: 1.65;
}
.grant-usage-list li + li {
  margin-top: 4px;
}
.grant-usage-list--detail {
  list-style: disc;
  padding-left: 18px;
}
.grant-usage-list--detail li + li {
  margin-top: 6px;
}
.grant-usage-list--detail strong {
  color: #334155;
}
.grant-guide-toggle {
  align-self: flex-start;
  padding: 0;
}
.grant-form-card {
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid #dbeafe;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}
.grant-form-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.grant-form-card-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.grant-form-card-meta {
  font-size: 12px;
  color: #64748b;
}
.grant-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 14px;
}
.grant-form-item--span-2 {
  grid-column: span 2;
}
.grant-form-item--full {
  grid-column: 1 / -1;
}
.grant-scope-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.grant-resource-count {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: #64748b;
}
.grant-form :deep(.grant-segmented.el-segmented) {
  width: 100%;
  max-width: 100%;
}
.grant-form :deep(.grant-segmented .el-segmented__group) {
  width: 100%;
}
.grant-form :deep(.grant-segmented .el-segmented__item) {
  flex: 1 1 0;
  min-width: 0;
}
.grant-form :deep(.grant-segmented .el-segmented__item-label) {
  overflow: visible;
  text-overflow: unset;
  white-space: nowrap;
  padding: 0 10px;
  font-size: 13px;
}
.grant-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.grant-form :deep(.el-form-item__label) {
  padding-bottom: 4px;
  font-size: 12px;
  color: #64748b;
}
.grant-form .el-select,
.grant-form .el-date-editor {
  width: 100%;
}
.grant-form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #e8edf7;
}
.grant-records {
  flex: 1;
  min-height: 0;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid #e8edf7;
  background: #fff;
}
.grant-records-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.grant-records-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.grant-records-meta {
  font-size: 12px;
  color: #64748b;
}
.grants-table {
  width: 100%;
}
.grant-resource-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.45;
}
.grant-resource-name {
  color: #0f172a;
  font-weight: 600;
}
.grant-resource-meta {
  font-size: 11px;
  color: #94a3b8;
  word-break: break-all;
}
.form-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding-top: 4px; }
.sub-tabs { margin-top: 8px; }
.dialog-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.role-bind-list { display: grid; gap: 10px; margin-top: 16px; }
@media (max-width: 1280px) {
  .hero-guide { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .split, .role-split { grid-template-columns: 1fr; }
  .grant-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grant-form-item--span-2 { grid-column: span 1; }
  .preview-summary { grid-template-columns: 1fr; }
  .preview-metric-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .hero-guide { grid-template-columns: 1fr; }
  .grant-form-grid { grid-template-columns: 1fr; }
  .grant-form-card-head { flex-direction: column; align-items: flex-start; }
  .preview-head-tools { width: 100%; flex-direction: column; align-items: stretch; }
  .preview-user-select { width: 100%; }
  .preview-metric-row { grid-template-columns: 1fr; }
  .metric-grid, .dialog-grid { grid-template-columns: 1fr; }
  .panel-head { flex-direction: column; }
  .tools { width: 100%; flex-wrap: wrap; }
}
</style>
