/**
 * 用户 / 管理：工作台、看板已分 key、分页面。
 * 业务批注与协同仅用户端（任务书全栈 C 用户项 9）；管理员无此项，管理员第 9 项为系统配置等。
 */
export const menuGroups = [
  {
    id: 'portal-user',
    title: '',
    modules: [
      {
        key: 'workbench',
        role: 'USER',
        index: '1',
        title: '首页工作台',
        subtitle: '用户工作台：公告、个人最近看板。'
      },
      {
        key: 'dashboard',
        role: 'USER',
        index: '4',
        title: '我的看板',
        subtitle: '用户看板：个人/公共看板、布局 JSON；批注协同见独立菜单。'
      },
      {
        key: 'collaboration',
        role: 'USER',
        index: '9',
        title: '业务批注与协同',
        subtitle: '按看板查看与发表批注、评论；独立页便于后续接入 WebSocket、圈注等。'
      },
      {
        key: 'upload',
        role: 'USER',
        index: '2',
        title: '数据上传（Excel/CSV）',
        subtitle: '前端上传页面、数据预览表格、字段语义展示、多文件合并配置和零代码业务建模。'
      },
      {
        key: 'chat',
        role: 'USER',
        index: '3',
        title: '对话查询（自然语言生成图表）',
        subtitle: '自然语言生成 SQL，经 GraphRAG 上下文、SQL 审计和权限校验后渲染 ECharts 图表。'
      },
      {
        key: 'permission',
        role: 'USER',
        index: '6',
        title: '数据权限中心',
        subtitle: '个人权限展示、可访问数据源、敏感字段权限说明、权限申请与审批进度。'
      },
      {
        key: 'diagnosis',
        role: 'USER',
        index: '7',
        title: '智能诊断报告',
        subtitle: '异常数据一键生成诊断报告、GraphRAG 多跳根因链路、报告历史和 DOCX/PDF 导出。'
      },
    ]
  },
  {
    id: 'portal-admin',
    title: '',
    modules: [
      {
        key: 'adminWorkbench',
        role: 'ADMIN',
        index: '1',
        title: '首页工作台',
        subtitle: '管理员工作台：可与用户端独立接全平台统计、健康度等。'
      },
      {
        key: 'adminDashboard',
        role: 'ADMIN',
        index: '4',
        title: '看板管理',
        subtitle: '公共/全局看板与全平台看板维护（任务书管理员侧模块 3；与用户端「我的看板」分工）。'
      },
      {
        key: 'datasource',
        role: 'ADMIN',
        index: '5',
        title: '官方数据源管理',
        subtitle: '数据源新增/编辑/删除、连接测试、Schema 解析、字段中文注释、联邦库配置和权限绑定。'
      },
      {
        key: 'permissionAdmin',
        role: 'ADMIN',
        index: '6',
        title: '数据权限审批',
        subtitle: '管理员集中审核普通用户对上传表和官方库的数据访问申请，写入授权关系。'
      },
      {
        key: 'audit',
        role: 'ADMIN',
        index: '6',
        title: 'SQL安全审计中心',
        subtitle: '全量 SQL 日志、审计规则配置、风险等级、敏感脱敏、慢查询监控与导出。'
      },
      {
        key: 'adminChatHistory',
        role: 'ADMIN',
        index: '7',
        title: '管理员对话历史',
        subtitle: '全平台对话查询、SQL 生成、知识图谱匹配、推理过程与性能审计统一查看。'
      },
      {
        key: 'stackCConfig',
        role: 'ADMIN',
        index: '9',
        title: '系统配置（全栈C）',
        subtitle: '全局 KV 配置项维护；与 is_system_config 表联动。'
      },
      {
        key: 'performanceGovernance',
        role: 'ADMIN',
        index: '10',
        title: '性能治理中心',
        subtitle: 'JVM/慢查询/批处理任务/告警阈值；数据来自审计日志与全栈 C 接口。'
      }
    ]
  }
]

export const moduleMap = Object.fromEntries(
  menuGroups.flatMap((group) => group.modules.map((module) => [module.key, module]))
)
