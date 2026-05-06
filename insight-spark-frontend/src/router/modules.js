export const menuGroups = [
  {
    title: '',
    modules: [
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
      }
    ]
  },
  {
    title: '',
    modules: [
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
        key: 'diagnosis',
        role: 'ADMIN',
        index: '7',
        title: '智能诊断报告',
        subtitle: '异常数据一键生成诊断报告、GraphRAG 多跳根因链路、报告历史和 DOCX/PDF 导出。'
      },
      {
        key: 'knowledgeGraph',
        role: 'ADMIN',
        index: '7',
        title: '知识图谱与GraphRAG支撑',
        subtitle: '沉淀数据表、字段、敏感标签和诊断报告，作为智能诊断报告的多跳检索支撑。'
      },
      {
        key: 'audit',
        role: 'ADMIN',
        index: '6',
        title: 'SQL安全审计中心',
        subtitle: '全量 SQL 日志、审计规则配置、风险等级、敏感脱敏、慢查询监控与导出。'
      }
    ]
  }
]

export const moduleMap = Object.fromEntries(
  menuGroups.flatMap((group) => group.modules.map((module) => [module.key, module]))
)
