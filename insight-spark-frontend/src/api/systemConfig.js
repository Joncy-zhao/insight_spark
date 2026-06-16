import axios from 'axios'
import { restoreSessionHeader } from '../store/session'

const API_BASE = 'http://localhost:8080'

function unwrap(res) {
  if (res.data.code !== 200) throw new Error(res.data.message || '请求失败')
  return res.data.data
}

export async function fetchConfigSchema() {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/admin/system-config/schema`))
}

export async function saveConfigBatch(items) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}/api/c/admin/system-config/batch`, { items }))
}

export async function saveConfigItem(item) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}/api/c/admin/system-config`, item))
}

export async function resetConfigModule(moduleId) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/admin/system-config/modules/${moduleId}/reset`))
}

export async function fetchAdminAnnouncements() {
  restoreSessionHeader()
  return unwrap(await axios.get(`${API_BASE}/api/c/admin/announcements`))
}

export async function publishAnnouncement(payload) {
  restoreSessionHeader()
  return unwrap(await axios.post(`${API_BASE}/api/c/admin/announcements`, payload))
}

export async function updateAnnouncementStatus(id, publishStatus) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}/api/c/admin/announcements/${id}/status`, { publishStatus }))
}

export async function updateAnnouncementPin(id, pinned) {
  restoreSessionHeader()
  return unwrap(await axios.put(`${API_BASE}/api/c/admin/announcements/${id}/pin`, { pinned }))
}

export async function fetchConfigUserCandidates(keyword = '') {
  restoreSessionHeader()
  const res = await axios.get(`${API_BASE}/api/c/admin/system-config/user-candidates`, {
    params: keyword ? { keyword } : {}
  })
  return unwrap(res)
}

export const BINDING_LABELS = {
  SQL_AUDIT: { text: 'SQL 审计', type: 'success' },
  NEO4J: { text: 'Neo4j', type: 'success' },
  AI_CHART: { text: 'AI 图表', type: 'success' },
  AI_SERVICE: { text: 'AI 服务只读', type: 'info' },
  DATASOURCE: { text: '数据源', type: 'success' },
  ENVIRONMENT: { text: 'application.yml', type: 'warning' },
  RUNTIME: { text: '运行时只读', type: 'info' },
  RUNTIME_CONFIG: { text: '保存即生效', type: 'success' },
  SYSTEM_CONFIG: { text: 'KV 存储', type: '' },
  STORE_ONLY: { text: '待接入', type: 'info' }
}

export const MODULE_META = {
  AI: {
    desc: 'Text-to-SQL、大模型、Neo4j 与 GraphRAG 推理参数',
    purpose: '定义自然语言转 SQL 与知识图谱增强推理的基础能力，直接影响对话查询质量与安全审计联动。',
    steps: [
      '核对 LLM 提供商、默认模型与采样温度是否符合企业规范',
      '配置 Neo4j 连接、GraphRAG 召回 TopK 与路由网关',
      '保存本模块；只读项需同步修改 application.yml 或 AI 服务'
    ],
    tip: '保存后新发起的对话查询与推理任务将立即使用最新参数。'
  },
  SECURITY: {
    desc: 'SQL 拦截、敏感脱敏、密码策略与访问频率限制',
    purpose: '管控 SQL 执行安全边界，防止越权查询、敏感字段泄露与高频恶意访问。',
    steps: [
      '启用并调整 SQL 拦截、慢查询与熔断相关阈值',
      '维护敏感字段脱敏规则与白名单策略',
      '保存后 SQL 审计与查询入口即时生效'
    ],
    tip: '建议先在「对话查询实验室」验证规则，再应用到生产环境。'
  },
  PERFORMANCE: {
    desc: 'Redis 缓存、看板预热、连接池与批处理调度',
    purpose: '优化系统吞吐与响应速度，降低数据库与 AI 服务压力。',
    steps: [
      '按需开启语义缓存并设置 TTL',
      '配置看板预热 Cron 与批处理并发上限',
      '与「性能治理中心」联动观察 JVM 与慢查询指标'
    ],
    tip: '性能类参数变更建议低峰期操作，并关注缓存命中率变化。'
  },
  UPLOAD: {
    desc: '文件上传大小、格式、解析与存储策略',
    purpose: '控制 Excel / CSV 等业务文件的上传边界与解析行为。',
    steps: [
      '设置单文件大小、允许格式与并发上传限制',
      '配置解析规则、存储路径与去重策略',
      '保存后用户上传入口按新规则校验'
    ],
    tip: '缩小上传限制前请确认现有业务文件规格仍可满足。'
  },
  DATASOURCE: {
    desc: '连接池、超时重连、心跳与跨库访问策略',
    purpose: '管理官方数据源连接行为，保障查询稳定与连接池健康。',
    steps: [
      '调整连接池上限、超时与心跳探测间隔',
      '配置跨库联邦与单用户访问频次',
      '保存后在「数据源管理」中验证连接探测结果'
    ],
    tip: '连接池参数变更会影响全平台并发查询能力。'
  },
  INTERACTION: {
    desc: 'SSE 流式、图表渲染、对话与语音交互',
    purpose: '定制前端交互体验，包括流式输出、图表默认样式与语音播报。',
    steps: [
      '配置 SSE / 流式响应与图表渲染默认参数',
      '调整对话历史保留、前端缓存等交互策略',
      '保存后用户端对话与看板渲染即时生效'
    ],
    tip: '交互参数多为体验类配置，可按业务场景逐步调优。'
  },
  NOTIFICATION: {
    desc: '预警推送、公告规则与异常告警渠道',
    purpose: '配置系统通知触达方式，并在本模块直接发布公告。',
    steps: [
      '选择 Email / 钉钉等告警渠道并填写 Webhook',
      '调整公告受众、置顶与优先级规则',
      '使用下方公告面板发布平台通知'
    ],
    tip: '钉钉机器人若启用加签，需同时填写 secret 参数。'
  }
}
