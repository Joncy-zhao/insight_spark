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
  AI: { icon: '🤖', desc: 'Text-to-SQL、大模型、Neo4j 知识图谱与 GraphRAG 推理参数' },
  SECURITY: { icon: '🛡️', desc: 'SQL 拦截、敏感脱敏、密码策略、慢查询与接口频率限制' },
  PERFORMANCE: { icon: '⚡', desc: 'Redis 缓存、看板预热、慢查询熔断、连接池与批处理调度' },
  UPLOAD: { icon: '📁', desc: '上传大小/格式、解析规则、存储、权限与去重策略' },
  DATASOURCE: { icon: '🗄️', desc: '连接池、超时重连、心跳、联邦跨库与访问频次' },
  INTERACTION: { icon: '💬', desc: 'SSE 流式、图表渲染、对话配置、前端优化与语音播报' },
  NOTIFICATION: { icon: '🔔', desc: '预警推送、公告规则、异常告警与系统更新通知' }
}
