import axios from 'axios'

export const API_BASE = 'http://localhost:8080'

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000
})

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
