import { http } from './http'
import type {
  Bucket,
  DeviceRequest,
  DeviceView,
  DeviceWithToken,
  Field,
  FieldDetail,
  FieldRequest,
  Layout,
  LoginResponse,
  Plot,
  PlotPatch,
  SensorMetric,
  Series,
  TraitView,
  Trial,
  TrialRequest,
  UserView,
  Variety,
  VarietyRequest,
} from './types'

export * from './types'
export { setToken, setUnauthorizedHandler } from './http'

const data = <T>(p: Promise<{ data: T }>) => p.then((r) => r.data)

export const authApi = {
  login: (username: string, password: string) =>
    data<LoginResponse>(http.post('/api/auth/login', { username, password })),
  me: () => data<UserView>(http.get('/api/auth/me')),
  changePassword: (oldPassword: string, newPassword: string) =>
    data<void>(http.put('/api/auth/password', { oldPassword, newPassword })),
}

export const fieldApi = {
  list: () => data<Field[]>(http.get('/api/fields')),
  detail: (id: number) => data<FieldDetail>(http.get(`/api/fields/${id}`)),
  create: (req: FieldRequest) => data<FieldDetail>(http.post('/api/fields', req)),
  update: (id: number, req: FieldRequest) => data<FieldDetail>(http.put(`/api/fields/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/fields/${id}`)),
  updateCodes: (id: number, codes: Record<number, string>) =>
    data<Plot[]>(http.put(`/api/fields/${id}/plot-codes`, { codes })),
  patchPlots: (id: number, patch: PlotPatch) => data<Plot[]>(http.patch(`/api/fields/${id}/plots`, patch)),
}

export const varietyApi = {
  list: () => data<Variety[]>(http.get('/api/varieties')),
  create: (req: VarietyRequest) => data<Variety>(http.post('/api/varieties', req)),
  update: (id: number, req: VarietyRequest) => data<Variety>(http.put(`/api/varieties/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/varieties/${id}`)),
}

export const trialApi = {
  list: (fieldId?: number) => data<Trial[]>(http.get('/api/trials', { params: { fieldId } })),
  create: (req: TrialRequest) => data<Trial>(http.post('/api/trials', req)),
  remove: (id: number) => data<void>(http.delete(`/api/trials/${id}`)),
  layout: (id: number) => data<Layout>(http.get(`/api/trials/${id}/layout`)),
  assign: (id: number, plotIds: number[], attribute: 'VARIETY' | 'REPLICATE', value: number | null) =>
    data<Layout>(http.put(`/api/trials/${id}/plantings`, { plotIds, attribute, value })),
  createLink: (id: number, plotIds: number[], controlPlotIds: number[], label: string) =>
    data<Layout>(http.post(`/api/trials/${id}/control-links`, { plotIds, controlPlotIds, label })),
  deleteLink: (id: number, linkId: number) => data<Layout>(http.delete(`/api/trials/${id}/control-links/${linkId}`)),
}

export const traitApi = {
  list: () => data<TraitView[]>(http.get('/api/traits')),
  setEnabled: (id: number, enabled: boolean) => data<void>(http.put(`/api/traits/${id}/enabled`, { enabled })),
}

export const deviceApi = {
  metrics: () => data<SensorMetric[]>(http.get('/api/sensor-metrics')),
  list: (fieldId?: number) => data<DeviceView[]>(http.get('/api/devices', { params: { fieldId } })),
  create: (req: DeviceRequest) => data<DeviceWithToken>(http.post('/api/devices', req)),
  update: (id: number, req: DeviceRequest) => data<DeviceView>(http.put(`/api/devices/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/devices/${id}`)),
  regenerateToken: (id: number) => data<DeviceWithToken>(http.post(`/api/devices/${id}/token`)),
  readings: (id: number, metric: string, from: string, to: string, bucket: Bucket) =>
    data<Series>(http.get(`/api/devices/${id}/readings`, { params: { metric, from, to, bucket } })),
}
