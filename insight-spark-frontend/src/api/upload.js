import { http, unwrap } from './http'

const multipart = {
  headers: { 'Content-Type': 'multipart/form-data' }
}

export const uploadFile = (formData) => http.post('/api/data/upload', formData, multipart).then(unwrap)
export const uploadBatch = (formData) => http.post('/api/data/upload-batch', formData, multipart).then(unwrap)
export const uploadFileAsync = (formData) => http.post('/api/data/upload-async', formData, multipart).then(unwrap)
export const uploadBatchAsync = (formData) => http.post('/api/data/upload-batch-async', formData, multipart).then(unwrap)
export const getUploadTask = (taskId) => http.get(`/api/data/upload-task/${taskId}`).then(unwrap)

export const fetchTables = () => http.get('/api/data/tables').then(unwrap)
export const fetchFields = (tableName) => http.get(`/api/data/tables/${tableName}/fields`).then(unwrap)
export const updateField = (tableName, columnName, payload) =>
  http.post(`/api/data/tables/${tableName}/fields/${columnName}`, payload).then(unwrap)

export const previewTable = (tableName, limit = 10) =>
  http.get(`/api/data/tables/${tableName}/preview`, { params: { limit } }).then(unwrap)

export const previewTablePage = (tableName, page = 1, pageSize = 10) =>
  http.get(`/api/data/tables/${tableName}/preview-page`, { params: { page, pageSize } }).then(unwrap)

export const renameTable = (tableName, displayName) =>
  http.post(`/api/data/tables/${tableName}/rename`, { displayName }).then(unwrap)

export const deleteTable = (tableName) => http.post(`/api/data/tables/${tableName}/delete`).then(unwrap)
export const exportTable = (tableName) => http.get(`/api/data/tables/${tableName}/export`, { responseType: 'blob' })

export const getDataQuality = (tableName) => http.get(`/api/data/tables/${tableName}/quality`).then(unwrap)

export const getFieldStatistics = (tableName, columnName) =>
  http.get(`/api/data/tables/${tableName}/fields/${columnName}/statistics`).then(unwrap)

export const detectAnomalies = (tableName, columnName) =>
  http.get(`/api/data/tables/${tableName}/fields/${columnName}/anomalies`).then(unwrap)

export const getFieldDistribution = (tableName, columnName) =>
  http.get(`/api/data/tables/${tableName}/fields/${columnName}/distribution`).then(unwrap)

export const batchReplace = (tableName, columnName, payload) =>
  http.post(`/api/data/tables/${tableName}/fields/${columnName}/batch-replace`, payload).then(unwrap)

export const getCleaningStrategy = (tableName) => http.get(`/api/data/tables/${tableName}/cleaning-strategy`).then(unwrap)
export const applyCleaningStrategy = (tableName, payload) =>
  http.post(`/api/data/tables/${tableName}/apply-cleaning-strategy`, payload).then(unwrap)
export const activateCleanedTable = (tableName, payload = {}) =>
  http.post(`/api/data/tables/${tableName}/activate-cleaned`, payload).then(unwrap)

export const deleteRows = (tableName, payload) => http.post(`/api/data/tables/${tableName}/delete-rows`, payload).then(unwrap)

export const deleteColumn = (tableName, columnName) =>
  http.post(`/api/data/tables/${tableName}/fields/${columnName}/delete`).then(unwrap)

export const transformData = (tableName, columnName, payload) =>
  http.post(`/api/data/tables/${tableName}/fields/${columnName}/transform`, payload).then(unwrap)

export const updateCell = (tableName, rowId, columnName, payload) =>
  http.post(`/api/data/tables/${tableName}/rows/${rowId}/fields/${columnName}`, payload).then(unwrap)

export const validateFile = (formData) => http.post('/api/data/validate-file', formData, multipart).then(unwrap)
export const checkDuplicate = (formData) => http.post('/api/data/check-duplicate', formData, multipart).then(unwrap)

export const fetchBusinessModels = (enterpriseOnly = false) =>
  http.get('/api/data/business-models', { params: { enterpriseOnly } }).then(unwrap)

export const saveBusinessModel = (payload) => http.post('/api/data/business-models', payload).then(unwrap)

export const publishBusinessModelById = (modelId, published) =>
  http.post(`/api/data/business-models/${modelId}/publish`, { published }).then(unwrap)

export const applyBusinessModelById = (modelId, tableName) =>
  http.post(`/api/data/business-models/${modelId}/apply`, { tableName }).then(unwrap)

export const uploadTemplate = (formData) => http.post('/api/data/templates/upload', formData, multipart).then(unwrap)
export const fetchTemplates = () => http.get('/api/data/templates').then(unwrap)

export const saveBusinessModelFromTemplate = (payload) =>
  http.post('/api/data/business-model/from-template', payload).then(unwrap)
