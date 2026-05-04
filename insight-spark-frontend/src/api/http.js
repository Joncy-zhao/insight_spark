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
    throw new Error(body.message)
  }
  return body.data ?? body
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

  const message = error?.response?.data?.message || error?.message || '请求失败'
  return Promise.reject(new Error(message))
}

http.interceptors.response.use(response => response, normalizeError)
axios.interceptors.response.use(response => response, normalizeError)
