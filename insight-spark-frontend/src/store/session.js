import { computed, ref } from 'vue'
import axios from 'axios'

const API_BASE = 'http://localhost:8080'
const saved = JSON.parse(localStorage.getItem('insight_auth') || 'null')

export const authToken = ref(saved?.token || '')
export const currentUser = ref(saved?.user || null)
export const userPermissionCodes = ref(saved?.user?.permissionCodes || saved?.permissionCodes || [])
export const currentRole = computed(() => currentUser.value?.role || 'GUEST')
export const isAuthenticated = computed(() => Boolean(authToken.value && currentUser.value))

const isSuperAdmin = (role, codes = []) =>
  role === 'SUPER_ADMIN' || (codes || []).includes('operation:super-admin')

export const isSuperAdminUser = () => isSuperAdmin(currentUser.value?.role, userPermissionCodes.value)

export const hasPermission = (code) => {
  if (isSuperAdminUser()) return true
  if (!code) return true
  return userPermissionCodes.value.includes(code)
}

export const setSession = ({ token, user }) => {
  authToken.value = token
  const codes = user?.permissionCodes || []
  userPermissionCodes.value = codes
  currentUser.value = user ? { ...user, permissionCodes: codes } : null
  localStorage.setItem('insight_auth', JSON.stringify({ token, user: currentUser.value }))
  localStorage.setItem('token', token)
  axios.defaults.headers.common.Authorization = `Bearer ${token}`
}

export const clearSession = () => {
  authToken.value = ''
  currentUser.value = null
  userPermissionCodes.value = []
  localStorage.removeItem('insight_auth')
  localStorage.removeItem('token')
  delete axios.defaults.headers.common.Authorization
}

export const restoreSessionHeader = () => {
  if (authToken.value) {
    axios.defaults.headers.common.Authorization = `Bearer ${authToken.value}`
  }
}

export const refreshSessionProfile = async () => {
  restoreSessionHeader()
  const res = await axios.get(`${API_BASE}/api/auth/me`)
  const body = res.data
  if (body?.code && body.code !== 200) {
    throw new Error(body.message || '会话刷新失败')
  }
  const data = body?.data
  if (!data) return
  const codes = data.permissionCodes || []
  userPermissionCodes.value = codes
  currentUser.value = {
    ...currentUser.value,
    ...data,
    permissionCodes: codes
  }
  localStorage.setItem('insight_auth', JSON.stringify({ token: authToken.value, user: currentUser.value }))
}

export const switchRole = (role) => {
  if (currentUser.value) {
    currentUser.value = { ...currentUser.value, role }
  }
}
