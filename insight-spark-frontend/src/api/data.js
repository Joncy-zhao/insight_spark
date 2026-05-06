import { http, unwrap } from './http'

export const uploadDataFile = (formData) => http.post('/api/data/upload', formData).then(unwrap)
export const fetchDataTables = () => http.get('/api/data/tables').then(unwrap)
export const fetchTableFields = (tableName) => http.get(`/api/data/tables/${tableName}/fields`).then(unwrap)
export const fetchTablePreview = (tableName) => http.get(`/api/data/tables/${tableName}/preview`).then(unwrap)
export const updateTableField = (tableName, columnName, fieldData) => http.post(`/api/data/tables/${tableName}/fields/${columnName}`, fieldData).then(unwrap)
