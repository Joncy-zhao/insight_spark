import { http, unwrap } from './http'

export const fetchDatasources = () => http.get('/api/datasources').then(unwrap)
export const saveDatasource = (payload) => http.post('/api/datasources', payload).then(unwrap)
export const testDatasourceConnection = (datasourceId) => http.post(`/api/datasources/${datasourceId}/test`).then(unwrap)
export const syncDatasourceSchema = (datasourceId) => http.post(`/api/datasources/${datasourceId}/sync-schema`).then(unwrap)
export const updateDatasourceStatus = (datasourceId, status) =>
  http.post(`/api/datasources/${datasourceId}/status`, { status }).then(unwrap)
export const fetchSchemaTables = (datasourceId) => http.get(`/api/datasources/${datasourceId}/schema/tables`).then(unwrap)
export const fetchSchemaFields = (datasourceId, tableName) =>
  http.get(`/api/datasources/${datasourceId}/schema/tables/${tableName}/fields`).then(unwrap)
export const updateSchemaField = (fieldId, payload) => http.post(`/api/datasources/schema/fields/${fieldId}`, payload).then(unwrap)
