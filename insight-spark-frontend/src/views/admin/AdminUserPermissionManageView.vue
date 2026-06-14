<template>
  <section class="aup-page">
    <header class="hero-card">
      <div class="hero-copy">
        <el-tag effect="dark" class="hero-tag">RBAC · 数据行权限 · 授权校验</el-tag>
        <h1>用户与权限管理</h1>
        <p>按“创建用户 → 绑定角色 → 配置角色权限 → 授权数据 → 预览校验”的流程完成权限闭环。字段级权限已移除，当前只管理用户、角色、菜单/操作/数据资源授权。</p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="openUser()">新增用户</el-button>
          <el-button size="large" @click="activeTab = 'grants'">去授权数据</el-button>
          <el-button size="large" :loading="loading" :icon="Refresh" @click="loadAll">刷新</el-button>
        </div>
      </div>
      <div class="hero-guide">
        <div v-for="step in workflowSteps" :key="step.no" class="guide-step" :class="{ active: activeTab === step.tab }" @click="activeTab = step.tab">
          <span>{{ step.no }}</span>
          <div><strong>{{ step.title }}</strong><small>{{ step.desc }}</small></div>
        </div>
      </div>
    </header>

    <div class="metric-grid">
      <article v-for="item in metrics" :key="item.label" class="metric-card">
        <div class="metric-dot" :class="`is-${item.tone}`"></div>
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
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
        <section class="panel">
          <div class="panel-head"><div><span class="section-kicker">STEP 2</span><h2>角色管理与可视化权限</h2><p>角色决定“能看到哪些菜单、能做哪些操作、默认拥有哪些数据范围”。点击左侧角色后，在右侧勾选权限并保存。</p></div><el-button type="primary" @click="openRole()">新增角色</el-button></div>
          <div class="split">
            <el-table :data="roles" height="470" class="pretty-table" highlight-current-row @row-click="selectRole">
              <el-table-column prop="roleCode" label="编码" width="110" />
              <el-table-column prop="roleName" label="角色" width="130" />
              <el-table-column prop="parentRoleCode" label="继承自" width="100"><template #default="{ row }">{{ row.parentRoleCode || '-' }}</template></el-table-column>
              <el-table-column prop="dataScope" label="数据范围" width="100" />
              <el-table-column prop="permissionCount" label="权限数" width="90" />
              <el-table-column label="操作" width="130"><template #default="{ row }"><el-button link type="primary" @click.stop="openRole(row)">编辑</el-button><el-button link @click.stop="selectRole(row)">配置</el-button></template></el-table-column>
            </el-table>
            <div class="permission-editor">
              <div class="editor-title"><div><span>当前角色</span><h3>{{ selectedRole?.roleName || '请先选择角色' }}</h3></div><el-tag v-if="selectedRole">{{ selectedRole.roleCode }}</el-tag></div>
              <el-empty v-if="!selectedRole" description="点击左侧角色开始配置权限" />
              <template v-else>
                <el-alert type="info" :closable="false" show-icon title="这里配置的是角色能力；具体某张表/某个看板的访问权，请到“数据授权/回收”。" />
                <el-checkbox-group v-model="selectedPermissionCodes" class="permission-groups">
                  <div v-for="group in permissionGroups" :key="group.key" class="permission-group">
                    <strong>{{ group.title }}</strong>
                    <el-checkbox v-for="p in group.items" :key="p.permissionCode" :label="p.permissionCode">{{ p.permissionName || p.permissionCode }}</el-checkbox>
                  </div>
                </el-checkbox-group>
                <el-button type="primary" @click="saveRolePermissionSelection">保存角色权限</el-button>
              </template>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="数据授权/回收" name="grants">
        <section class="panel">
          <div class="panel-head"><div><span class="section-kicker">STEP 3</span><h2>管理员主动授权 / 回收权限</h2><p>先选授权对象，再选资源类型和资源。支持一次选择多个资源进行批量授权或回收。</p></div><el-button @click="loadGrants">刷新授权</el-button></div>
          <div class="grant-layout">
            <div class="grant-card">
              <div class="mini-guide"><strong>授权怎么做？</strong><ol><li>选择用户或角色</li><li>选择上传表/官方库/看板</li><li>选择 READ 或 EDIT</li><li>点击批量授权，最后去预览校验</li></ol></div>
              <el-form :model="grantForm" label-position="top" class="grant-form">
                <el-form-item label="1. 授权对象"><el-segmented v-model="grantForm.targetType" :options="['USER', 'ROLE']" /></el-form-item>
                <el-form-item label="2. 选择对象"><el-select v-model="grantForm.targetId" filterable placeholder="请选择用户或角色"><el-option v-for="item in grantTargets" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
                <el-form-item label="3. 资源类型"><el-segmented v-model="grantForm.scope" :options="['TABLE', 'OFFICIAL', 'DASHBOARD']" /></el-form-item>
                <el-form-item label="4. 选择资源"><el-select v-model="grantForm.resources" filterable multiple collapse-tags collapse-tags-tooltip placeholder="可批量选择多个资源"><el-option v-for="item in currentResources" :key="item.tableName" :label="item.displayName" :value="item.tableName" /></el-select></el-form-item>
                <el-form-item label="5. 权限"><el-segmented v-model="grantForm.permissionType" :options="['READ', 'EDIT']" /></el-form-item>
                <el-form-item label="6. 有效期"><el-date-picker v-model="grantForm.expireAt" value-format="YYYY-MM-DD" placeholder="为空表示长期" /></el-form-item>
                <div class="form-actions"><el-button type="primary" :disabled="!canSubmitGrant" @click="batchGrant">批量授权</el-button><el-button type="danger" plain :disabled="!canSubmitGrant" @click="batchRevoke">批量回收</el-button><el-button :disabled="!grantForm.targetId || grantForm.targetType !== 'USER'" @click="loadPreview(grantForm.targetId)">预览该用户</el-button></div>
              </el-form>
            </div>
            <el-table :data="grants" height="390" stripe class="pretty-table grants-table" empty-text="暂无授权记录。请先在左侧完成授权。"><el-table-column prop="scope" label="类型" width="90" /><el-table-column prop="targetId" label="对象" width="130" /><el-table-column prop="resource" label="资源" min-width="180" show-overflow-tooltip /><el-table-column prop="permissionType" label="权限" width="90" /><el-table-column label="有效期" width="140"><template #default="{ row }">{{ row.expireAt || '长期' }}</template></el-table-column><el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="danger" @click="revokeSingle(row)">回收</el-button></template></el-table-column></el-table>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="权限预览与校验" name="preview">
        <section class="panel">
          <div class="panel-head"><div><span class="section-kicker">STEP 4</span><h2>权限预览与校验</h2><p>用管理员视角检查一个用户最终生效的角色链、菜单/操作/数据权限和可访问资源。</p></div><el-select v-model="previewUserId" filterable placeholder="选择用户进行校验" @change="loadPreview"><el-option v-for="u in users" :key="u.userId" :label="`${u.nickname}（${u.userId}）`" :value="u.userId" /></el-select></div>
          <div v-if="preview.user" class="preview-grid">
            <article class="preview-card"><span>用户</span><strong>{{ preview.user.nickname }}</strong><small>{{ preview.user.userId }} / {{ preview.user.status }}</small></article>
            <article class="preview-card"><span>角色链</span><strong>{{ (preview.effectiveRoles || []).join(' → ') || '-' }}</strong><small>含继承角色</small></article>
            <article class="preview-card"><span>权限数</span><strong>{{ preview.permissions?.length || 0 }}</strong><small>菜单 / 操作 / 数据</small></article>
            <article class="preview-card"><span>可访问资源</span><strong>{{ preview.accessibleTables?.length || 0 }}</strong><small>上传表 + 官方库</small></article>
          </div>
          <el-tabs v-if="preview.user" class="sub-tabs"><el-tab-pane label="有效权限"><el-table :data="preview.permissions" height="280" class="pretty-table"><el-table-column prop="permissionType" label="类型" width="100" /><el-table-column prop="permissionName" label="权限" min-width="180" /><el-table-column prop="roleCode" label="来源角色" width="120" /><el-table-column prop="resourceScope" label="资源范围" min-width="160" /></el-table></el-tab-pane><el-tab-pane label="授权资源"><el-table :data="preview.dataGrants" height="280" class="pretty-table"><el-table-column prop="scope" label="类型" width="100" /><el-table-column prop="resource" label="资源" min-width="200" /><el-table-column prop="permissionType" label="权限" width="100" /><el-table-column label="有效期" width="160"><template #default="{ row }">{{ row.expireAt || '长期' }}</template></el-table-column></el-table></el-tab-pane><el-tab-pane label="可访问数据"><el-table :data="preview.accessibleTables" height="280" class="pretty-table"><el-table-column prop="sourceType" label="来源" width="100" /><el-table-column prop="displayName" label="名称" min-width="200" /><el-table-column prop="ownerId" label="归属" width="140" /></el-table></el-tab-pane></el-tabs>
          <el-empty v-else description="请选择一个用户进行权限校验"><el-button type="primary" @click="activeTab = 'users'">去选择/创建用户</el-button></el-empty>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="userDialog" title="用户信息" width="620px"><el-form :model="userForm" label-position="top" class="dialog-grid"><el-form-item label="用户ID"><el-input v-model="userForm.userId" :disabled="!!editingUser" placeholder="如 user_001" /></el-form-item><el-form-item label="用户名"><el-input v-model="userForm.username" placeholder="登录名" /></el-form-item><el-form-item label="昵称"><el-input v-model="userForm.nickname" placeholder="页面显示名称" /></el-form-item><el-form-item label="邮箱"><el-input v-model="userForm.email" /></el-form-item><el-form-item label="手机号"><el-input v-model="userForm.phone" /></el-form-item><el-form-item label="初始角色"><el-select v-model="userForm.role"><el-option v-for="r in roles" :key="r.roleCode" :label="`${r.roleName}（${r.roleCode}）`" :value="r.roleCode" /></el-select></el-form-item><el-form-item label="状态"><el-segmented v-model="userForm.status" :options="['ACTIVE', 'DISABLED']" /></el-form-item><el-form-item label="密码"><el-input v-model="userForm.password" show-password placeholder="新增必填，编辑留空不修改" /></el-form-item></el-form><template #footer><el-button @click="userDialog = false">取消</el-button><el-button type="primary" @click="saveUser">保存用户</el-button></template></el-dialog>
    <el-dialog v-model="roleDialog" title="角色信息" width="560px"><el-form :model="roleForm" label-position="top"><el-form-item label="角色编码"><el-input v-model="roleForm.roleCode" :disabled="!!editingRole" placeholder="如 DATA_ANALYST" /></el-form-item><el-form-item label="角色名称"><el-input v-model="roleForm.roleName" /></el-form-item><el-form-item label="继承自"><el-select v-model="roleForm.parentRoleCode" clearable><el-option v-for="r in roles" :key="r.roleCode" :label="r.roleName" :value="r.roleCode" /></el-select></el-form-item><el-form-item label="角色层级"><el-input-number v-model="roleForm.roleLevel" :min="1" /></el-form-item><el-form-item label="数据范围"><el-select v-model="roleForm.dataScope"><el-option label="本人" value="SELF" /><el-option label="授权" value="GRANTED" /><el-option label="全部" value="ALL" /></el-select></el-form-item><el-form-item label="描述"><el-input v-model="roleForm.description" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="roleDialog = false">取消</el-button><el-button type="primary" @click="saveRole">保存角色</el-button></template></el-dialog>
    <el-dialog v-model="roleBindDialog" title="绑定用户角色" width="500px"><el-alert type="info" :closable="false" show-icon title="一个用户可绑定多个角色，最终权限会合并并继承父角色。" /><el-checkbox-group v-model="roleBindCodes" class="role-bind-list"><el-checkbox v-for="r in roles" :key="r.roleCode" :label="r.roleCode">{{ r.roleName }}（{{ r.roleCode }}）</el-checkbox></el-checkbox-group><template #footer><el-button @click="roleBindDialog = false">取消</el-button><el-button type="primary" @click="saveRoleBind">保存绑定</el-button></template></el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
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
const grantTargets = computed(() => grantForm.targetType === 'USER'
  ? users.value.map(u => ({ label: `${u.nickname || u.username}（${u.userId}）`, value: u.userId }))
  : roles.value.map(r => ({ label: `${r.roleName}（${r.roleCode}）`, value: r.roleCode })))
const currentResources = computed(() => grantForm.scope === 'OFFICIAL' ? resources.officialTables : grantForm.scope === 'DASHBOARD' ? resources.dashboards : resources.uploadTables)
const canSubmitGrant = computed(() => grantForm.targetId && grantForm.resources.length && grantForm.permissionType)

watch(() => grantForm.targetType, () => { grantForm.targetId = '' })
watch(() => grantForm.scope, () => { grantForm.resources = [] })

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
const loadResources = async () => { Object.assign(resources, await fetchAdminPermissionResources()) }
const loadGrants = async () => { grants.value = await fetchAdminDataGrants() }

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
const selectRole = (row) => { selectedRole.value = row; selectedPermissionCodes.value = (row.permissions || []).map(p => p.permissionCode) }
const saveRolePermissionSelection = async () => {
  const permissions = selectedPermissionCodes.value.map(code => permissionMap.value[code]).filter(Boolean)
  await saveAdminRolePermissions(selectedRole.value.roleCode, permissions)
  ElMessage.success('角色权限已保存')
  await Promise.all([loadRoles(), loadCatalog()])
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
const loadPreview = async (userId) => { if (!userId) return; activeTab.value = 'preview'; previewUserId.value = userId; preview.value = await fetchAdminPermissionPreview(userId) }

onMounted(loadAll)
</script>

<style scoped>
.aup-page { display: grid; gap: 16px; color: #13213a; }
.hero-card { position: relative; overflow: hidden; display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 24px; padding: 28px; border-radius: 30px; background: radial-gradient(circle at 12% 20%, rgba(59,130,246,.2), transparent 32%), linear-gradient(135deg, #f8fbff 0%, #eef5ff 48%, #f8f3ff 100%); border: 1px solid rgba(37,99,235,.14); box-shadow: 0 24px 60px rgba(37,99,235,.12); }
.hero-tag { border: 0; background: linear-gradient(135deg, #2563eb, #7c3aed); }
.hero-copy h1 { margin: 12px 0 8px; color: #0f172a; font-size: 32px; letter-spacing: -.04em; }
.hero-copy p { max-width: 900px; margin: 0; color: #526179; line-height: 1.9; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
.hero-guide { display: grid; gap: 10px; }
.guide-step { display: grid; grid-template-columns: 42px 1fr; gap: 12px; align-items: center; padding: 12px; border: 1px solid rgba(191,219,254,.9); border-radius: 18px; background: rgba(255,255,255,.68); cursor: pointer; transition: .18s; }
.guide-step:hover, .guide-step.active { transform: translateY(-1px); border-color: #60a5fa; box-shadow: 0 12px 26px rgba(37,99,235,.12); }
.guide-step span { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 14px; background: #eff6ff; color: #2563eb; font-weight: 800; }
.guide-step strong, .guide-step small { display: block; }
.guide-step small { color: #64748b; margin-top: 3px; }
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.metric-card, .panel, .preview-card { border: 1px solid #e8edf7; border-radius: 24px; background: rgba(255,255,255,.94); box-shadow: 0 16px 42px rgba(15,23,42,.055); }
.metric-card { position: relative; overflow: hidden; padding: 18px; }
.metric-dot { width: 10px; height: 10px; border-radius: 999px; margin-bottom: 12px; box-shadow: 0 0 0 7px rgba(37,99,235,.08); }
.metric-dot.is-blue { background: #2563eb; } .metric-dot.is-green { background: #10b981; } .metric-dot.is-purple { background: #7c3aed; } .metric-dot.is-orange { background: #f59e0b; } .metric-dot.is-red { background: #ef4444; }
.metric-card span, .metric-card small, .preview-card span, .preview-card small { display: block; color: #64748b; }
.metric-card strong { display: block; margin: 8px 0; color: #0f172a; font-size: 30px; }
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
.split { display: grid; grid-template-columns: minmax(0, 1fr) 460px; gap: 16px; }
.permission-editor { padding: 18px; border-radius: 22px; border: 1px solid #dbeafe; background: linear-gradient(180deg, #f8fbff, #ffffff); }
.editor-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.editor-title span { color: #64748b; font-size: 12px; }
.editor-title h3 { margin: 4px 0 0; color: #0f172a; }
.permission-groups { display: grid; gap: 14px; margin: 16px 0; max-height: 330px; overflow: auto; }
.permission-group { display: grid; gap: 8px; padding: 12px; border-radius: 16px; background: #f8fafc; border: 1px solid #edf2f7; }
.permission-group strong { color: #2563eb; }
.grant-layout { display: grid; grid-template-columns: 430px minmax(0, 1fr); gap: 18px; }
.grant-card { padding: 18px; border-radius: 22px; background: linear-gradient(180deg, #f8fbff, #fff); border: 1px solid #dbeafe; }
.mini-guide { margin-bottom: 14px; padding: 14px; border-radius: 16px; background: #eff6ff; color: #334155; }
.mini-guide strong { color: #1d4ed8; }
.mini-guide ol { margin: 8px 0 0; padding-left: 18px; color: #64748b; line-height: 1.7; }
.grant-form { display: grid; gap: 10px; }
.grant-form .el-select, .grant-form .el-date-editor { width: 100%; }
.form-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding-top: 4px; }
.preview-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.preview-card { padding: 18px; }
.preview-card strong { display: block; margin: 8px 0; color: #0f172a; font-size: 20px; word-break: break-all; }
.sub-tabs { margin-top: 8px; }
.dialog-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.role-bind-list { display: grid; gap: 10px; margin-top: 16px; }
@media (max-width: 1280px) { .hero-card, .grant-layout, .split { grid-template-columns: 1fr; } .metric-grid, .preview-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .metric-grid, .preview-grid, .dialog-grid { grid-template-columns: 1fr; } .panel-head { flex-direction: column; } .tools { width: 100%; flex-wrap: wrap; } }
</style>
