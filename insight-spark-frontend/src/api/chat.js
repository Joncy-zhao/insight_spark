import { http, unwrap } from './http'

export const askQuestion = (payload) => http.post('/api/chat/ask', payload).then(unwrap)
