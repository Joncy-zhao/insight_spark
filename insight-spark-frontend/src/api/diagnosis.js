import { http, unwrap } from './http'

export const runDiagnosisReport = (payload) => http.post('/api/diagnosis/run', payload).then(unwrap)
export const fetchDiagnosisReports = () => http.get('/api/diagnosis/reports').then(unwrap)
export const fetchDiagnosisReportDetail = (reportId) => http.get(`/api/diagnosis/reports/${reportId}`).then(unwrap)
