import { download, downloadPost, http } from './http'
import type {
  AnalysisQuery,
  AnalysisResult,
  Bucket,
  CustomTraitRequest,
  EntryRequest,
  HistoryView,
  ImportMapping,
  ImportPreview,
  ImportResult,
  MultiYear,
  ObservationQuery,
  ObservationView,
  PageResult,
  SavedChart,
  PlotDetail,
  PlotValue,
  Screen,
  TrialReport,
  TraitValue,
  TrialStatus,
  VarietySummary,
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
  TrialBook,
  TrialBookRequest,
  AppModule,
  CorrectionCreate,
  CorrectionStatus,
  CorrectionSummary,
  CorrectionView,
  LinkModuleRequest,
  QualityReport,
  TraitDef,
  UserView,
  Variety,
  VarietyRequest,
  Candidates,
  CrossRequest,
  CrossResult,
  FounderItem,
  LandRequest,
  LineDetail,
  LineGraph,
  LineKind,
  LineRow,
  LineStatus,
  LineUpdateRequest,
  PlantResult,
  PlantValueView,
  SeasonRequest,
  SeasonView,
  SelectionRequest,
} from './types'

import type { FieldLayout } from '@/shared/fieldLayout'

export * from './types'
export { ApiError, isNetworkError, setToken, setUnauthorizedHandler } from './http'

const data = <T>(p: Promise<{ data: T }>) => p.then((r) => r.data)

export const authApi = {
  /** client 填 MOBILE 时是手机端登录，令牌有效期更长 */
  login: (username: string, password: string, client?: 'MOBILE') =>
    data<LoginResponse>(http.post('/api/auth/login', { username, password, client })),
  me: () => data<UserView>(http.get('/api/auth/me')),
  changePassword: (oldPassword: string, newPassword: string) =>
    data<void>(http.put('/api/auth/password', { oldPassword, newPassword })),
}

export const fieldApi = {
  /** scope：不填只列原来品种比较的实验田；breeding 只列育种的地块；all 全部 */
  list: (scope?: 'breeding' | 'all') => data<Field[]>(http.get('/api/fields', { params: { scope } })),
  detail: (id: number) => data<FieldDetail>(http.get(`/api/fields/${id}`)),
  create: (req: FieldRequest) => data<FieldDetail>(http.post('/api/fields', req)),
  update: (id: number, req: FieldRequest) => data<FieldDetail>(http.put(`/api/fields/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/fields/${id}`)),
  updateCodes: (id: number, codes: Record<number, string>) =>
    data<Plot[]>(http.put(`/api/fields/${id}/plot-codes`, { codes })),
  patchPlots: (id: number, patch: PlotPatch) => data<Plot[]>(http.patch(`/api/fields/${id}/plots`, patch)),
  saveLayout: (id: number, layout: FieldLayout) => data<FieldDetail>(http.put(`/api/fields/${id}/layout`, layout)),
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
  update: (id: number, req: TrialRequest) => data<Trial>(http.put(`/api/trials/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/trials/${id}`)),
  book: (id: number) => data<TrialBook>(http.get(`/api/trials/${id}/book`)),
  saveBook: (id: number, req: TrialBookRequest) => data<TrialBook>(http.put(`/api/trials/${id}/book`, req)),
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
  setImportant: (id: number, important: boolean) => data<void>(http.put(`/api/traits/${id}/important`, { important })),
  setRange: (id: number, minValue: number | null, maxValue: number | null) =>
    data<TraitDef>(http.put(`/api/traits/${id}/range`, { minValue, maxValue })),
  createCustom: (req: CustomTraitRequest) => data<TraitView>(http.post('/api/traits', req)),
  removeCustom: (id: number) => data<void>(http.delete(`/api/traits/${id}`)),
  setAliases: (id: number, aliases: string[]) => data<string[]>(http.put(`/api/traits/${id}/aliases`, { aliases })),
}

export const observationApi = {
  create: (req: EntryRequest) => data<ObservationView[]>(http.post('/api/observations', req)),
  list: (q: ObservationQuery) => data<PageResult<ObservationView>>(http.get('/api/observations', { params: q })),
  update: (id: number, version: number, observedOn: string, value: TraitValue, note: string | null) =>
    data<ObservationView>(http.put(`/api/observations/${id}`, { version, observedOn, note, ...value })),
  remove: (id: number) => data<void>(http.delete(`/api/observations/${id}`)),
  history: (id: number) => data<HistoryView[]>(http.get(`/api/observations/${id}/history`)),
  byIds: (ids: number[]) => data<ObservationView[]>(http.get('/api/observations/by-ids', { params: { ids: ids.join(',') } })),
  exportExcel: (q: Omit<ObservationQuery, 'page' | 'size'>, filename: string) =>
    download('/api/observations/export', q, filename),
  status: (trialId: number) => data<TrialStatus>(http.get(`/api/trials/${trialId}/status`)),
  plotDetail: (trialId: number, plotId: number) => data<PlotDetail>(http.get(`/api/trials/${trialId}/plots/${plotId}`)),
  traitValues: (trialId: number, traitId: number) =>
    data<PlotValue[]>(http.get(`/api/trials/${trialId}/trait-values`, { params: { traitId } })),
  setAbnormal: (trialId: number, plotIds: number[], abnormal: boolean, note: string | null) =>
    data<TrialStatus>(http.put(`/api/trials/${trialId}/abnormal`, { plotIds, abnormal, note })),
}

export const statsApi = {
  varietySummary: (trialId: number, traitId: number) =>
    data<VarietySummary>(http.get('/api/stats/variety-summary', { params: { trialId, traitId } })),
  multiYear: (fieldId: number, traitId: number) =>
    data<MultiYear>(http.get('/api/stats/multi-year', { params: { fieldId, traitId } })),
  screen: (fieldId?: number) => data<Screen>(http.get('/api/screen', { params: { fieldId } })),
  trialReport: (trialId: number) => data<TrialReport>(http.get('/api/stats/trial-report', { params: { trialId } })),
  exportTrialReport: (trialId: number, filename: string) =>
    download('/api/stats/trial-report/export', { trialId }, filename),
  /** 按河南省小麦品种试验记载本的样子导出整个试验 */
  exportRecordBook: (trialId: number, filename: string) =>
    download('/api/stats/record-book/export', { trialId }, filename),
}

export const importApi = {
  preview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return data<ImportPreview>(http.post('/api/imports/preview', form, { timeout: 120000 }))
  },
  run: (file: File, mapping: ImportMapping) => {
    const form = new FormData()
    form.append('file', file)
    form.append('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }))
    return data<ImportResult>(http.post('/api/imports/run', form, { timeout: 300000 }))
  },
  template: (trialId: number, filename: string) => download('/api/imports/template', { trialId }, filename),
}

export const analysisApi = {
  query: (q: AnalysisQuery) => data<AnalysisResult>(http.post('/api/analysis/query', q, { timeout: 60000 })),
  exportExcel: (q: AnalysisQuery, filename: string) => downloadPost('/api/analysis/export', q, filename),
  charts: <C>() => data<SavedChart<C>[]>(http.get('/api/analysis/charts')),
  createChart: <C>(name: string, config: C) => data<SavedChart<C>>(http.post('/api/analysis/charts', { name, config })),
  updateChart: <C>(id: number, name: string, config: C) =>
    data<SavedChart<C>>(http.put(`/api/analysis/charts/${id}`, { name, config })),
  removeChart: (id: number) => data<void>(http.delete(`/api/analysis/charts/${id}`)),
}

export const userApi = {
  list: () => data<UserView[]>(http.get('/api/users')),
  create: (req: { username: string; displayName: string; role: string; password: string }) =>
    data<UserView>(http.post('/api/users', req)),
  resetPassword: (id: number, password: string) => data<void>(http.put(`/api/users/${id}/password`, { password })),
  setEnabled: (id: number, enabled: boolean) => data<void>(http.put(`/api/users/${id}/enabled`, { enabled })),
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

export const moduleApi = {
  list: () => data<AppModule[]>(http.get('/api/modules')),
  setEnabled: (code: string, enabled: boolean) => data<AppModule>(http.put(`/api/modules/${code}/enabled`, { enabled })),
  createLink: (req: LinkModuleRequest) => data<AppModule>(http.post('/api/modules/links', req)),
  updateLink: (code: string, req: LinkModuleRequest) => data<AppModule>(http.put(`/api/modules/links/${code}`, req)),
  removeLink: (code: string) => data<void>(http.delete(`/api/modules/links/${code}`)),
}

export const qualityApi = {
  check: (trialId: number) => data<QualityReport>(http.get('/api/quality', { params: { trialId } })),
  ack: (trialId: number, key: string, note: string | null) =>
    data<void>(http.post('/api/quality/ack', { trialId, key, note })),
  unack: (trialId: number, key: string) => data<void>(http.delete('/api/quality/ack', { params: { trialId, key } })),
}

export const correctionApi = {
  create: (req: CorrectionCreate) => data<CorrectionView>(http.post('/api/corrections', req)),
  list: (params: { status?: CorrectionStatus; trialId?: number } = {}) =>
    data<CorrectionView[]>(http.get('/api/corrections', { params })),
  summary: () => data<CorrectionSummary>(http.get('/api/corrections/summary')),
  approve: (id: number, note: string | null) => data<CorrectionView>(http.post(`/api/corrections/${id}/approve`, { note })),
  reject: (id: number, note: string | null) => data<CorrectionView>(http.post(`/api/corrections/${id}/reject`, { note })),
  withdraw: (id: number) => data<void>(http.post(`/api/corrections/${id}/withdraw`)),
}

export const seasonApi = {
  list: () => data<SeasonView[]>(http.get('/api/seasons')),
  create: (req: SeasonRequest) => data<SeasonView>(http.post('/api/seasons', req)),
  update: (id: number, req: SeasonRequest) => data<SeasonView>(http.put(`/api/seasons/${id}`, req)),
  remove: (id: number) => data<void>(http.delete(`/api/seasons/${id}`)),
  createLand: (id: number, req: LandRequest) => data<FieldDetail>(http.post(`/api/seasons/${id}/fields`, req)),
  removeLand: (id: number, fieldId: number) => data<void>(http.delete(`/api/seasons/${id}/fields/${fieldId}`)),
  cleanup: (id: number) => data<LineRow[]>(http.get(`/api/seasons/${id}/cleanup`)),
}

export const lineApi = {
  list: (params: { keyword?: string; status?: LineStatus; kind?: LineKind } = {}) =>
    data<LineRow[]>(http.get('/api/lines', { params })),
  graph: () => data<LineGraph>(http.get('/api/lines/graph')),
  detail: (id: number) => data<LineDetail>(http.get(`/api/lines/${id}`)),
  createFounders: (items: FounderItem[]) => data<LineRow[]>(http.post('/api/lines/founders', { items })),
  adopt: (varietyIds: number[]) => data<LineRow[]>(http.post('/api/lines/adopt', { varietyIds })),
  cross: (req: CrossRequest) => data<CrossResult>(http.post('/api/lines/crosses', req)),
  select: (id: number, req: SelectionRequest) => data<LineRow[]>(http.post(`/api/lines/${id}/selections`, req)),
  update: (id: number, req: LineUpdateRequest) => data<LineRow>(http.put(`/api/lines/${id}`, req)),
  setStatus: (lineIds: number[], status: LineStatus) => data<LineRow[]>(http.put('/api/lines/status', { lineIds, status })),
  remove: (id: number) => data<void>(http.delete(`/api/lines/${id}`)),
  savePlantValues: (id: number, values: { traitId: number; valueNum: number | null; valueText: string | null; valueDate: string | null }[]) =>
    data<PlantValueView[]>(http.put(`/api/lines/${id}/plant-values`, { values })),
  exportExcel: (id: number, filename: string) => download(`/api/lines/${id}/export`, {}, filename),
}

export const breedingApi = {
  candidates: (fieldId: number) => data<Candidates>(http.get(`/api/breeding/fields/${fieldId}/candidates`)),
  plant: (fieldId: number, plotIds: number[], lineId: number | null) =>
    data<PlantResult>(http.put(`/api/breeding/fields/${fieldId}/plantings`, { plotIds, lineId })),
  setGeneration: (fieldId: number, plotIds: number[], generation: number | null) =>
    data<PlantResult>(http.put(`/api/breeding/fields/${fieldId}/generation`, { plotIds, generation })),
}
