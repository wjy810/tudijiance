// 数据质量：问题的分类和级别的中文名，录入时的"超出常见范围"提醒
import type { QualityKind, QualityLevel, TraitDef } from '@/shared/api'

export const QUALITY_LEVEL: Record<QualityLevel, { label: string; tone: 'clay' | 'wheat' | 'sky' }> = {
  ERROR: { label: '错误', tone: 'clay' },
  WARN: { label: '提醒', tone: 'wheat' },
  INFO: { label: '提示', tone: 'sky' },
}

export const QUALITY_KIND: Record<QualityKind, { label: string; desc: string }> = {
  LAYOUT: { label: '试验布置', desc: '品种、重复、对照有没有设好' },
  MISSING: { label: '漏录', desc: '重要项目已经开始录了，还有小区没录' },
  RANGE: { label: '超出常见范围', desc: '可能录错了，确认一下' },
  OUTLIER: { label: '和其它小区差得多', desc: '可能录错了，确认一下' },
  DATE_ORDER: { label: '日期先后不对', desc: '播种 → 出苗 → 抽穗 → 扬花 → 成熟' },
  DATE_SEASON: { label: '日期不对', desc: '还没到，或者不在这一季' },
  CONFLICT: { label: '录了两个不同的值', desc: '统计只用最近录的那个' },
  YIELD_CV: { label: '产量误差变异系数', desc: '标准第 9 章：≥ 15% 可以申请报废' },
  VARIETY_CV: { label: '重复间差得多', desc: '同一品种各重复的小区产量' },
  YIELD_GAIN: { label: '增产幅度过大', desc: '标准第 9 章：比对照增产 ≥ 30% 可以申请报废' },
  SEEDLING_LOSS: { label: '缺苗', desc: '标准第 9 章：2 个及以上小区缺苗率 ≥ 20% 可以申请报废' },
  ABNORMAL: { label: '标了异常', desc: '自然灾害等要在 72 小时内书面说明并上传照片' },
}

const PERCENT = [
  'overwinter_rate', 'lodging_first_area', 'lodging_final_area', 'stripe_rust_severity', 'stripe_rust_incidence',
  'leaf_rust_severity', 'leaf_rust_incidence', 'stem_rust_severity', 'stem_rust_incidence', 'scab_spike_rate',
  'sharp_eyespot_whitehead', 'crown_rot_whitehead', 'yellow_dwarf_incidence', 'sprouting_rate',
] as const

/** 标准项目默认的常见范围，和服务端 V10 迁移一致（单机版没有服务端，直接用这份） */
export const DEFAULT_TRAIT_RANGES: Readonly<Record<string, readonly [number, number]>> = {
  plant_height: [30, 150],
  basic_seedlings: [5, 50],
  max_tillers: [20, 200],
  effective_spikes: [15, 80],
  spike_rate: [15, 100],
  grains_per_spike: [15, 70],
  thousand_grain_weight: [25, 65],
  test_weight: [650, 860],
  black_germ_rate: [0, 30],
  off_type_count: [0, 50],
  off_type_rate: [0, 20],
  yield_per_mu: [150, 1000],
  whole_growth_days: [200, 290],
  growth_days: [180, 280],
  ...Object.fromEntries(PERCENT.map((c) => [c, [0, 100] as const])),
}

const plain = (n: number) => String(Math.round(n * 10000) / 10000)

/** 常见范围的文字：30–150 cm、≥ 0 % */
export function rangeText(t: Pick<TraitDef, 'minValue' | 'maxValue' | 'unit'>): string | null {
  const lo = t.minValue
  const hi = t.maxValue
  if (lo == null && hi == null) return null
  const unit = t.unit && t.unit !== '级' ? ` ${t.unit}` : ''
  if (lo == null) return `≤ ${plain(hi!)}${unit}`
  if (hi == null) return `≥ ${plain(lo)}${unit}`
  return `${plain(lo)}–${plain(hi)}${unit}`
}

/** 录入的值超出常见范围时返回一句提醒，没超出、不是数或没设范围时返回 null */
export function rangeWarning(t: Pick<TraitDef, 'minValue' | 'maxValue' | 'unit'>, value: number | null | undefined): string | null {
  if (value == null || !Number.isFinite(value)) return null
  const text = rangeText(t)
  if (!text) return null
  const low = t.minValue != null && value < t.minValue
  const high = t.maxValue != null && value > t.maxValue
  if (!low && !high) return null
  return `${low ? '偏低' : '偏高'}：常见范围 ${text}，确认没录错？`
}
