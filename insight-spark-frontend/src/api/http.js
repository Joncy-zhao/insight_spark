import axios from 'axios'

export const API_BASE = 'http://localhost:8080'

export const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000
})

export const unwrap = (response) => {
  const body = response.data
  if (body.code && body.code !== 200) {
    throw new Error(body.message)
  }
  return body.data ?? body
}
