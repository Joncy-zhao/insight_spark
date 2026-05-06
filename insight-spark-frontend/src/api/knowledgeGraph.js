import { http, unwrap } from './http'

export const syncKnowledgeGraph = () => http.post('/api/knowledge-graph/sync').then(unwrap)
export const fetchKnowledgeGraph = (limit = 120) => http.get('/api/knowledge-graph/graph', { params: { limit } }).then(unwrap)
export const searchKnowledgeGraph = (keyword, limit = 20) =>
  http.get('/api/knowledge-graph/search', { params: { keyword, limit } }).then(unwrap)
