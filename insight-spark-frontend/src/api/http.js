import axios from 'axios'
import { clearSession } from '../store/session'

export const API_BASE = 'http://localhost:8080'

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000
})

let sessionExpiredHandled = false

const readToken = () => {
  const directToken = localStorage.getItem('token')
  if (directToken) {
    return directToken
  }
  try {
    return JSON.parse(localStorage.getItem('insight_auth') || 'null')?.token || ''
  } catch (error) {
    return ''
  }
}

export const attachAuthHeader = (config) => {
  const token = readToken()
  config.headers = config.headers || {}
  config.headers.Authorization = `Bearer ${token}`
  return config
}

http.interceptors.request.use(attachAuthHeader)
axios.interceptors.request.use(attachAuthHeader)

export const unwrap = (response) => {
  const body = response.data
  if (body.code && body.code !== 200) {
    throw new Error(toFriendlyErrorMessage(body.message))
  }
  return body.data ?? body
}

const toFriendlyErrorMessage = (message) => {
  const raw = String(message || '').trim()
  if (!raw) return '请求失败，请稍后重试'
  if (raw.includes('JSON config is invalid') || raw.includes('Invalid JSON config')) {
    return 'JSON 格式不正确，请检查逗号、引号和括号是否完整。'
  }
  if (raw.includes('JSON config is too large')) {
    return '配置 JSON 过大，请删减配置后再保存。'
  }
  if (raw.includes('JSON config list is too large')) {
    return '配置数组过长，请减少列表项数量后再保存。'
  }
  if (raw.includes('JSON config text is too long')) {
    return raw.replace('JSON config text is too long:', '配置文本过长：')
  }
  if (raw.includes('JSON config contains unsafe key:')) {
    return raw.replace('JSON config contains unsafe key:', '检测到不安全配置字段：')
      + '，禁止配置脚本、函数入口或外链地址字段。'
  }
  if (raw.includes('JSON config contains unsafe content:')) {
    return raw.replace('JSON config contains unsafe content:', '检测到不安全配置内容：')
      + '，禁止在图表配置中写入脚本、函数体或外链 URL。'
  }
  if (/animation|dataZoom|dynamic|progressive|largeThreshold|ECharts/i.test(raw)
    && /(invalid|unsupported|error|failed|unsafe|too large|not allowed)/i.test(raw)) {
    return '图表动态渲染配置不正确，请检查动画、缩放、渐进渲染等设置后重试。'
  }
  if (raw === 'Network Error') return '网络连接失败，请检查后端服务是否已启动。'
  if (raw.includes('timeout')) return '请求超时，请稍后重试。'
  return raw
}

const normalizeError = (error) => {
  if (error?.response?.status === 401) {
    if (!sessionExpiredHandled) {
      sessionExpiredHandled = true
      clearSession()
      setTimeout(() => {
        sessionExpiredHandled = false
      }, 1000)
    }
    return Promise.reject(new Error('登录已失效，请重新登录'))
  }

  const message = toFriendlyErrorMessage(error?.response?.data?.message || error?.message || '请求失败')
  return Promise.reject(new Error(message))
}

http.interceptors.response.use(response => response, normalizeError)
axios.interceptors.response.use(response => response, normalizeError)
