export const menuGroups = [
  {
    id: 'portal-user',
    title: '',
    modules: [
      {
        key: 'workbench',
        role: 'USER',
        permissionCode: 'menu:user-workbench',
        index: '1',
        title: '首页工作台',
        subtitle: '用户工作台：公告、个人最近看板与快捷入口。'
      },
      {
        key: 'dashboard',
        role: 'USER',
        permissionCode: 'menu:dashboard',
        index: '4',
        title: '我的看板',
        subtitle: '管理私密与公共看板，维护布局 JSON、图表组件和数据快照。'
      },
      {
        key: 'collaboration',
        role: 'USER',
        index: '9',
        title: '业务批注与协同',
        subtitle: '按看板查看、发表批注和评论，沉淀业务经验。'
      },
      {
        key: 'upload',
        role: 'USER',
        permissionCode: 'menu:data-upload',
        index: '2',
        title: '数据上传（Excel/CSV）',
        subtitle: '上传、预览、字段语义展示、多文件合并配置和零代码业务建模。'
      },
      {
        key: 'chat',
        role: 'USER',
        permissionCode: 'menu:chat-analysis',
        index: '3',
        title: '灵犀智能洞察',
        subtitle: '自然语言生成 SQL，经 GraphRAG、SQL 审计和权限校验后渲染 ECharts 图表。'
      },
      {
        key: 'advancedAnalysis',
        role: 'USER',
        index: '8',
        title: '预测与情景模拟',
        subtitle: '管理时序预测方案、What-if 推演、预警规则、预警事件和推送记录。'
      },
      {
        key: 'permission',
        role: 'USER',
        permissionCode: 'menu:permission-center',
        index: '6',
        title: '数据权限中心',
        subtitle: '查看个人权限、可访问数据源、敏感字段说明、权限申请和审批进度。'
      },
      {
        key: 'diagnosis',
        role: 'USER',
        permissionCode: 'menu:diagnosis',
        index: '7',
        title: '智能诊断报告',
        subtitle: '异常数据一键生成诊断报告，支持 GraphRAG 归因链路、历史报告和导出。'
      }
    ]
  },
  {
    id: 'portal-admin',
    title: '',
    modules: [
      {
        key: 'adminWorkbench',
        role: 'ADMIN',
        permissionCode: 'menu:user-workbench',
        index: '1',
        title: '首页工作台',
        subtitle: '管理员工作台：平台统计、公告、数据源健康和引擎状态。'
      },
      {
        key: 'adminDashboard',
        role: 'ADMIN',
        permissionCode: 'menu:dashboard',
        index: '4',
        title: '看板管理',
        subtitle: '维护公共和全局看板配置。'
      },
      {
        key: 'datasource',
        role: 'ADMIN',
        permissionCode: 'menu:datasource-admin',
        index: '5',
        title: '官方数据源管理',
        subtitle: '数据源新增、编辑、连接测试、Schema 解析、字段中文注释、联邦库配置和权限绑定。'
      },
      {
        key: 'userPermissionManage',
        role: 'ADMIN',
        permissionCode: 'operation:rbac-manage',
        index: '6',
        title: '用户与权限管理',
        subtitle: '一个菜单内管理用户、角色、RBAC 权限、数据授权、字段级规则和权限预览。'
      },
      {
        key: 'permissionAdmin',
        role: 'ADMIN',
        permissionCode: 'menu:permission-approval',
        index: '6',
        title: '数据权限审批',
        subtitle: '集中审核普通用户对上传表和官方库的数据访问申请。'
      },
      {
        key: 'audit',
        role: 'ADMIN',
        permissionCode: 'menu:sql-audit',
        index: '6',
        title: 'SQL 安全审计中心',
        subtitle: '全量 SQL 日志、审计规则、风险等级、敏感脱敏、慢查询监控和导出。'
      },
      {
        key: 'adminChatHistory',
        role: 'ADMIN',
        index: '7',
        title: '对话历史',
        subtitle: '查看全平台对话查询、SQL 生成、知识图谱匹配、推理过程和性能审计。'
      },
      {
        key: 'adminChatQueryLab',
        role: 'ADMIN',
        index: '8',
        title: '对话查询实验室',
        subtitle: '跨数据源验证 Text-to-SQL、GraphRAG、SQL 安全、权限穿透和图表渲染效果。'
      },
      {
        key: 'aiChartRules',
        role: 'ADMIN',
        index: '9',
        title: 'AI图表推荐规则配置',
        subtitle: '配置趋势、对比、占比、明细与自定义推荐规则，统一 ECharts 渲染参数和企业视觉偏好。'
      },
      {
        key: 'performanceGovernance',
        role: 'ADMIN',
        index: '10',
        title: '性能治理中心',
        subtitle: 'JVM、慢查询、批处理任务和告警阈值治理。'
      },
      {
        key: 'stackCConfig',
        role: 'ADMIN',
        index: '11',
        title: '系统配置',
        subtitle: '维护全局 KV 配置项，与 is_system_config 表联动，分模块保存并即时生效。'
      }
    ]
  }
]

export const SUPER_ADMIN_ROLE = 'SUPER_ADMIN'
export const SUPER_ADMIN_PERMISSION = 'operation:super-admin'

export const moduleMap = Object.fromEntries(
  menuGroups.flatMap((group) => group.modules.map((module) => [module.key, module]))
)

moduleMap.collaborationRoom = {
  key: 'collaborationRoom',
  title: '协作批注',
  subtitle: '看板画布与批注、评论协作空间。'
}

export function isSuperAdmin(role, permissionCodes = []) {
  return role === SUPER_ADMIN_ROLE || permissionCodes.includes(SUPER_ADMIN_PERMISSION)
}

export function allAdminSidebarModules() {
  const adminGroup = menuGroups.find((group) => group.id === 'portal-admin')
  return adminGroup?.modules || []
}

export function canAccessModule(module, role, permissionCodes = []) {
  if (!module) return false
  if (isSuperAdmin(role, permissionCodes)) {
    return module.role === 'ADMIN' || module.role === 'ALL'
  }
  if (module.permissionCode) {
    return permissionCodes.includes(module.permissionCode)
  }
  return module.role === 'ALL' || module.role === role
}
