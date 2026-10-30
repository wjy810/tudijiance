// 和后端接口对应的数据类型

export type Role = 'ADMIN' | 'RECORDER'

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

export type FieldRequest = Omit<Field, 'id' | 'createdAt' | 'updatedAt'>

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

export type TraitCategory = 'PHENOLOGY' | 'MORPHOLOGY' | 'GROWTH' | 'STRESS' | 'DISEASE' | 'LAB' | 'YIELD'
export type TraitDataType = 'DATE' | 'NUMBER' | 'GRADE' | 'OPTION' | 'TEXT'

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
