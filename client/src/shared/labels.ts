// 各种取值的中文名称
import type {
  ControlType,
  PlotStatusCode,
  Role,
  DeviceStatus,
  Ecotype,
  PlotKind,
  QualityType,
  TraitCategory,
  TraitDataType,
  TrialType,
} from '@/shared/api'

export const ECOTYPE: Record<Ecotype, string> = {
  SPRING: '春性',
  WEAK_SPRING: '弱春性',
  SEMI_WINTER: '半冬性',
  WINTER: '冬性',
}

export const QUALITY_TYPE: Record<QualityType, string> = {
  STRONG: '强筋',
  MEDIUM_STRONG: '中强筋',
  MEDIUM: '中筋',
  WEAK: '弱筋',
}

export const CONTROL_TYPE: Record<ControlType, string> = {
  YIELD: '丰产对照',
  QUALITY: '优质对照',
  RESISTANCE: '抗性对照',
}

export const TRIAL_TYPE: Record<TrialType, string> = {
  REGIONAL: '区域试验',
  PRODUCTION: '生产试验',
  COMPARISON: '品比试验',
  OBSERVATION: '观察圃',
}

export const PLOT_KIND: Record<PlotKind, string> = {
  PLOT: '试验小区',
  GUARD: '保护区',
  PATH: '走道',
}

export const TRAIT_CATEGORY: Record<TraitCategory, string> = {
  OPERATION: '农事操作',
  PHENOLOGY: '物候期',
  MORPHOLOGY: '形态特征',
  GROWTH: '生育动态',
  STRESS: '抗逆性',
  DISEASE: '病虫害',
  LAB: '室内考种',
  YIELD: '产量',
  CUSTOM: '自定义',
}

export const TRAIT_DATA_TYPE: Record<TraitDataType, string> = {
  DATE: '日期',
  NUMBER: '数值',
  GRADE: '分级',
  OPTION: '选项',
  TEXT: '文字',
  OPERATION: '农事',
}

export const AGG_METHOD: Record<string, string> = {
  NONE: '不处理',
  AVG: '取平均值',
  MAX: '取最大值',
  MIN: '取最小值',
  MODE: '取最多值',
}

export const DEVICE_STATUS: Record<DeviceStatus, string> = {
  NEVER: '未上报',
  ONLINE: '在线',
  OFFLINE: '离线',
}

export const DEVICE_TYPE: Record<string, string> = {
  SOIL_3IN1: '土壤三合一（温度、湿度、pH）',
}

export const WHEAT_ZONES: { code: string; name: string }[] = [
  { code: 'Q1', name: '长江中下游冬麦区' },
  { code: 'Q2', name: '长江上游冬麦区' },
  { code: 'Q3', name: '黄淮冬麦区南片水地' },
  { code: 'Q4', name: '黄淮冬麦区北片水地' },
  { code: 'Q5', name: '北部冬麦区水地' },
  { code: 'Q6', name: '黄淮冬麦区旱肥地' },
  { code: 'Q7', name: '北部冬麦区旱地' },
  { code: 'Q8', name: '黄淮冬麦区旱薄地' },
  { code: 'Q9', name: '东北春麦区晚熟' },
  { code: 'Q10', name: '西北春麦区水地' },
  { code: 'Q11', name: '西北春麦区旱地' },
]

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X']

/** 重复号用罗马数字显示（I、II、III），超过 10 用阿拉伯数字。 */
export function replicateLabel(n: number): string {
  return ROMAN[n] ?? String(n)
}

export const ROLE: Record<Role, string> = {
  SYS_ADMIN: '系统管理员',
  TEACHER: '老师',
  STUDENT: '学生',
  GUEST: '游客',
}

/** 小区状态的名称和颜色（平面图、图例、大屏统一用这一套） */
export const PLOT_STATUS: Record<PlotStatusCode, { label: string; color: string }> = {
  NONE: { label: '未种植', color: '#f1ebdd' },
  GROWING: { label: '生长中', color: '#cfe2c2' },
  ABNORMAL: { label: '有异常', color: '#f0c6b6' },
  HARVESTED: { label: '已收获', color: '#efd497' },
  GUARD: { label: '保护区', color: '#ddd5c1' },
  PATH: { label: '走道', color: '#f7f2e7' },
}
