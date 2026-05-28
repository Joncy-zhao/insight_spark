import { http, unwrap } from './http'

export const parseAdvancedAnalysisIntent = (payload) =>
  http.post('/api/advanced-analysis/parse', payload).then(unwrap)

export const fetchAdvancedAnalysisFieldMeta = (payload) =>
  http.post('/api/advanced-analysis/field-meta', payload).then(unwrap)

export const runAdvancedForecast = (payload) =>
  http.post('/api/advanced-analysis/forecast', payload).then(unwrap)

export const runAdvancedForecastFromSeries = (payload) =>
  http.post('/api/advanced-analysis/forecast-series', payload).then(unwrap)

export const runAdvancedWhatIf = (payload) =>
  http.post('/api/advanced-analysis/what-if', payload).then(unwrap)
