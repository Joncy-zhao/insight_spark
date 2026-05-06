import { http, unwrap } from './http'

export const getCaptcha = () => http.get('/api/auth/captcha').then(unwrap)

export const login = (payload) => http.post('/api/auth/login', payload).then(unwrap)

export const register = (payload) => http.post('/api/auth/register', payload).then(unwrap)

export const logout = () => http.post('/api/auth/logout').then(unwrap)
