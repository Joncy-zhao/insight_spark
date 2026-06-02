import { http, unwrap } from './http'

export const listDashboards = () =>
  http.get('/api/c/dashboards').then(unwrap)

export const pinChartToDashboard = (dashboardId, payload) =>
  http.post(`/api/c/dashboards/${dashboardId}/pin-chart`, payload).then(unwrap)
