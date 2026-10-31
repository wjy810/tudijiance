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
  /** 表头别名（规范化后的写法）：导入表格时按它认出这一列是这个项目 */
  aliases: string[]
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
  /** 同时记下的表头别名 */
  aliases?: string[]
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

// ---------- 品种主要性状汇总表 ----------

export interface ReportColumn {
  traitId: number
  code: string
  name: string
  unit: string | null
}

export interface ReportRow {
  varietyId: number
  varietyName: string
  control: boolean
  plots: number
  /** 和 columns 一一对应，没有数据的是 null */
  values: (number | null)[]
  growthDaysVsCk: number | null
  yieldVsCkPercent: number | null
  yieldRank: number | null
}

export interface TrialReport {
  trialId: number
  trialName: string
  seasonYear: number
  fieldName: string
  controlVariety: string | null
  columns: ReportColumn[]
  rows: ReportRow[]
  hasGrowthDays: boolean
  hasYield: boolean
}

// ---------- 导入历史表格 ----------

export interface ImportPreviewSheet {
  name: string
  rowCount: number
  colCount: number
  /** 整张表，每个格子都是文字 */
  rows: string[][]
}

export interface ImportPreview {
  sheets: ImportPreviewSheet[]
}

/** COLUMNS：每列一个字段，第一行是表头；ROWS：每行一个字段，第一列是表头 */
export type ImportOrientation = 'COLUMNS' | 'ROWS'

export type ImportRole = 'PLOT_CODE' | 'VARIETY' | 'REPLICATE' | 'OBSERVED_ON' | 'TRAIT'

/** 选中的区域，行号、列号都从 0 开始，含首尾 */
export interface ImportRange {
  top: number
  left: number
  bottom: number
  right: number
}

/** 区域里第 index 个字段的用途 */
export interface ImportFieldMapping {
  index: number
  role: ImportRole
  traitId?: number | null
}

/** 页面上改过的格子（工作表里的行号、列号，从 0 开始） */
export interface CellEdit {
  row: number
  col: number
  value: string
}

export interface ImportMapping {
  trialId: number
  sheet: number
  range: ImportRange
  orientation: ImportOrientation
  fields: ImportFieldMapping[]
  observedOn: string | null
  assignLayout: boolean
  createVarieties: boolean
  edits: CellEdit[]
  rememberHeaders: boolean
  dryRun: boolean
}

export interface ImportCellError {
  /** Excel 行号（从 1 开始）和列字母 */
  row: number
  col: string
  /** 工作表里的行号、列号（从 0 开始），用来定位格子 */
  r: number
  c: number
  message: string
}

export interface ImportResult {
  dryRun: boolean
  records: number
  created: number
  duplicates: number
  errorCount: number
  errors: ImportCellError[]
  newVarieties: string[]
  layoutChanges: number
  aliasesRemembered: number
}

// ---------- 自助分析 ----------

export type AnalysisDimension = 'VARIETY' | 'YEAR' | 'REPLICATE' | 'PLOT' | 'FIELD' | 'TRAIT'
export type AnalysisAgg = 'AVG' | 'SUM' | 'MAX' | 'MIN' | 'MEDIAN' | 'COUNT' | 'CV'

export interface AnalysisDimensionSpec {
  type: AnalysisDimension
  traitId?: number | null
}

export interface AnalysisMeasureSpec {
  /** 不填且汇总方式是 COUNT 时数小区个数 */
  traitId?: number | null
  agg: AnalysisAgg
}

export interface AnalysisQuery {
  fieldId?: number | null
  years?: number[]
  trialIds?: number[]
  varietyIds?: number[]
  replicates?: number[]
  plotIds?: number[]
  dimensions: AnalysisDimensionSpec[]
  measures: AnalysisMeasureSpec[]
}

export interface AnalysisColumn {
  key: string
  label: string
  unit: string | null
  kind: 'DIMENSION' | 'MEASURE'
}

export interface AnalysisResult {
  columns: AnalysisColumn[]
  /** 每行先是各分组的名称，再是各统计值（没有数据时为 null） */
  rows: (string | number | null)[][]
  records: number
}

export interface SavedChart<C = unknown> {
  id: number
  name: string
  config: C
  createdBy: number
  createdByName: string | null
  updatedAt: string
}
