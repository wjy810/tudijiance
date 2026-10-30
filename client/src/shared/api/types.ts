// 和后端接口对应的数据类型

export type Role = 'SYS_ADMIN' | 'TEACHER' | 'STUDENT' | 'GUEST'

export interface UserView {
  id: number
  username: string
  displayName: string | null
  role: Role
  enabled: boolean
}

export interface LoginResponse {
  token: string
  expiresAt: string
  user: UserView
}

export interface Field {
  id: number
  name: string
  location: string | null
  latitude: number | null
  longitude: number | null
  altitudeM: number | null
  areaMu: number | null
  rowCount: number
  colCount: number
  soilType: string | null
  irrigated: boolean | null
  previousCrop: string | null
  remark: string | null
  createdAt: string
  updatedAt: string
}

export type FieldRequest = Omit<Field, 'id' | 'createdAt' | 'updatedAt'> & {
  /** 新生成小区的默认长和宽（米） */
  plotLengthM?: number | null
  plotWidthM?: number | null
}

export type PlotKind = 'PLOT' | 'GUARD' | 'PATH'

export interface Plot {
  id: number
  fieldId: number
  rowNo: number
  colNo: number
  code: string | null
  kind: PlotKind
  lengthM: number | null
  widthM: number | null
  plantingRows: number | null
  rowSpacingCm: number | null
}

export interface FieldDetail {
  field: Field
  plots: Plot[]
}

export interface PlotPatch {
  plotIds: number[]
  kind?: PlotKind
  lengthM?: number
  widthM?: number
  plantingRows?: number
  rowSpacingCm?: number
}

export type Ecotype = 'SPRING' | 'WEAK_SPRING' | 'SEMI_WINTER' | 'WINTER'
export type QualityType = 'STRONG' | 'MEDIUM_STRONG' | 'MEDIUM' | 'WEAK'
export type ControlType = 'YIELD' | 'QUALITY' | 'RESISTANCE'

export interface Variety {
  id: number
  name: string
  code: string | null
  breeder: string | null
  ecotype: Ecotype | null
  qualityType: QualityType | null
  controlType: ControlType | null
  remark: string | null
  createdAt: string
}

export type VarietyRequest = Omit<Variety, 'id' | 'createdAt'>

export type TrialType = 'REGIONAL' | 'PRODUCTION' | 'COMPARISON' | 'OBSERVATION'

export interface Trial {
  id: number
  fieldId: number
  seasonYear: number
  name: string
  trialType: TrialType
  wheatZone: string | null
  replicateCount: number | null
  remark: string | null
  createdAt: string
}

export type TrialRequest = Omit<Trial, 'id' | 'createdAt'>

export interface PlantingView {
  plotId: number
  varietyId: number | null
  replicateNo: number | null
}

export interface LinkView {
  id: number
  label: string
  plots: { plotId: number; control: boolean }[]
}

export interface Layout {
  trial: Trial
  plantings: PlantingView[]
  links: LinkView[]
}

export type TraitCategory =
  | 'OPERATION' | 'PHENOLOGY' | 'MORPHOLOGY' | 'GROWTH' | 'STRESS' | 'DISEASE' | 'LAB' | 'YIELD' | 'CUSTOM'
export type TraitDataType = 'DATE' | 'NUMBER' | 'GRADE' | 'OPTION' | 'TEXT' | 'OPERATION'

export interface TraitDef {
  id: number
  code: string
  name: string
  category: TraitCategory
  dataType: TraitDataType
  unit: string | null
  precisionDigits: number | null
  aggMethod: 'NONE' | 'AVG' | 'MAX' | 'MIN' | 'MODE'
  surveyTime: string | null
  stdClause: string | null
  isStandard: boolean
  isComputed: boolean
  enabled: boolean
  sortOrder: number
  description: string | null
}

export interface TraitOption {
  id: number
  traitId: number
  valueCode: string
  label: string
  description: string | null
  sortOrder: number
}

export interface TraitView {
  trait: TraitDef
  options: TraitOption[]
}

export interface SensorMetric {
  code: string
  name: string
  unit: string | null
  minValid: number | null
  maxValid: number | null
  precisionDigits: number
  enabled: boolean
  sortOrder: number
}

export type DeviceStatus = 'NEVER' | 'ONLINE' | 'OFFLINE'

export interface LatestValue {
  value: number
  measuredAt: string
  outOfRange: boolean
}

export interface DeviceView {
  id: number
  code: string
  name: string
  deviceType: string
  fieldId: number | null
  posRow: number | null
  posCol: number | null
  depthCm: number | null
  simulated: boolean
  status: DeviceStatus
  lastSeenAt: string | null
  remark: string | null
  mqttTopic: string
  latest: Record<string, LatestValue>
}

export interface DeviceRequest {
  code: string
  name: string
  deviceType: string
  fieldId: number | null
  posRow: number | null
  posCol: number | null
  depthCm: number | null
  simulated: boolean
  remark: string | null
}

export interface DeviceWithToken {
  device: DeviceView
  token: string
}

export type Bucket = 'RAW' | 'HOUR' | 'DAY'

export interface SeriesPoint {
  t: string
  value: number
  min: number | null
  max: number | null
}

export interface Series {
  metric: string
  unit: string | null
  bucket: Bucket
  points: SeriesPoint[]
}

export interface CustomTraitRequest {
  name: string
  dataType: 'DATE' | 'NUMBER' | 'OPTION' | 'TEXT'
  unit?: string | null
  precisionDigits?: number | null
  options?: string[]
  description?: string | null
}

// ---------- 录入的数据 ----------

/** 录入的值。不同类型的项目用不同的字段。 */
export interface TraitValue {
  valueNum: number | null
  valueText: string | null
  valueDate: string | null
}

export interface EntryRequest extends TraitValue {
  trialId: number
  traitId: number
  items: { plotId: number; clientUuid: string }[]
  observedOn: string
  note: string | null
}

export interface ObservationView extends TraitValue {
  id: number
  trialId: number
  plotId: number | null
  plotCode: string | null
  varietyId: number | null
  varietyName: string | null
  replicateNo: number | null
  traitId: number
  traitName: string
  traitCategory: TraitCategory
  dataType: TraitDataType
  unit: string | null
  standardTrait: boolean
  observedOn: string
  displayValue: string
  note: string | null
  source: string
  recordedBy: string | null
  recordedAt: string
  updatedBy: string | null
  updatedAt: string | null
  version: number
}

export interface PageResult<T> {
  total: number
  items: T[]
}

export interface HistoryView {
  action: 'CREATE' | 'UPDATE' | 'DELETE'
  oldValue: string | null
  newValue: string | null
  user: string
  at: string
}

export interface ObservationQuery {
  trialId: number
  varietyId?: number
  plotId?: number
  traitId?: number
  category?: string
  from?: string
  to?: string
  recordedBy?: number
  page?: number
  size?: number
}

// ---------- 平面图状态和小区详情 ----------

export type PlotStatusCode = 'NONE' | 'GROWING' | 'ABNORMAL' | 'HARVESTED' | 'GUARD' | 'PATH'

export interface PlotStatus {
  plotId: number
  status: PlotStatusCode
  enteredToday: boolean
  abnormalNote: string | null
}

export interface Indicators {
  totalPlots: number
  planted: number
  enteredToday: number
  abnormal: number
  harvested: number
  weekEntries: number
  currentStage: string | null
}

export interface TrialStatus {
  plots: PlotStatus[]
  indicators: Indicators
}

export interface LatestTraitValue {
  traitId: number
  traitName: string
  category: TraitCategory
  standard: boolean
  sortOrder: number
  displayValue: string
  observedOn: string | null
  /** 由其它项目算出来的（全生育期、折合亩产等），不能直接录入 */
  computed: boolean
}

/** 平面图"按指标"着色用：一个小区某个项目的值 */
export interface PlotValue {
  plotId: number
  value: number
  displayValue: string
}

export interface PlotDetail {
  plot: Plot
  plantingId: number | null
  variety: Variety | null
  replicateNo: number | null
  abnormal: boolean
  abnormalNote: string | null
  latest: LatestTraitValue[]
  records: ObservationView[]
}

// ---------- 统计和大屏 ----------

export interface VarietyRow {
  varietyId: number
  varietyName: string
  control: boolean
  plots: number
  value: number
  vsCkPercent: number | null
  rank: number
}

export interface VarietySummary {
  traitId: number
  traitName: string
  unit: string | null
  seasonYear: number
  controlVariety: string | null
  rows: VarietyRow[]
}

export interface MultiYear {
  traitId: number
  traitName: string
  unit: string | null
  years: number[]
  series: { varietyId: number; varietyName: string; control: boolean; values: (number | null)[] }[]
}

export interface ScreenPlot {
  plotId: number
  rowNo: number
  colNo: number
  code: string | null
  varietyName: string | null
  status: PlotStatusCode
  abnormalNote: string | null
}

export interface Screen {
  fieldName: string | null
  trialName: string | null
  seasonYear: number | null
  rowCount: number
  colCount: number
  indicators: Indicators | null
  plots: ScreenPlot[]
  yieldRows: VarietyRow[]
  /** 亩产对比用的是哪一年（当年还没收获时是上一年） */
  yieldYear: number | null
  trend: { year: number; value: number }[]
  /** 最新录入：同一次给多个小区录的同一个值合成一条，plotCount 是小区数 */
  recent: { at: string; user: string; plotCode: string; plotCount: number; traitName: string; value: string }[]
  generatedAt: string
}
