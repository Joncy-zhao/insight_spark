import { computed, ref } from 'vue'
import axios from 'axios'

const saved = JSON.parse(localStorage.getItem('insight_auth') || 'null')

export const authToken = ref(saved?.token || '')
export const currentUser = ref(saved?.user || null)
export const currentRole = computed(() => currentUser.value?.role || 'GUEST')
export const isAuthenticated = computed(() => Boolean(authToken.value && currentUser.value))

export const setSession = ({ token, user }) => {
  authToken.value = token
  currentUser.value = user
  localStorage.setItem('insight_auth', JSON.stringify({ token, user }))
  axios.defaults.headers.common.Authorization = `Bearer ${token}`
}

export const clearSession = () => {
  authToken.value = ''
  currentUser.value = null
  localStorage.removeItem('insight_auth')
  delete axios.defaults.headers.common.Authorization
}

export const restoreSessionHeader = () => {
  if (authToken.value) {
    axios.defaults.headers.common.Authorization = `Bearer ${authToken.value}`
  }
}

export const switchRole = (role) => {
  if (currentUser.value) {
    currentUser.value = { ...currentUser.value, role }
  }
}
