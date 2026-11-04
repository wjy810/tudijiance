// 试验记载本：按《河南省小麦品种试验记载本（2024-2025）》的封面、基本情况、产量计算参数、气象资料和品种评价。
// 内容存在服务器 trial_book 表里（字段 → 值），字段在这里定义。田间记载、考种、产量各列已经是录入项目，不在这里
import type { Field, Layout, Plot, Trial, Variety } from '@/shared/api'
import { TRIAL_TYPE } from '@/shared/labels'

export type BookFieldType = 'text' | 'number' | 'date' | 'select' | 'textarea'

export interface BookField {
  key: string
  label: string
  type: BookFieldType
  unit?: string
  options?: string[]
  hint?: string
  /** 占几列（一行 4 列），默认 1；多行文字默认占满 */
  span?: 1 | 2 | 4
}

export interface BookGroup {
  key: string
  title: string
  desc?: string
  fields: BookField[]
}

const area = (key: string, label: string): BookField => ({ key, label, type: 'textarea', span: 4 })

export const BOOK_GROUPS: BookGroup[] = [
  {
    key: 'cover',
    title: '封面',
    fields: [
      { key: 'year', label: '年度', type: 'text', hint: '例如 2024-2025' },
      { key: 'trialType', label: '试验区别', type: 'text', hint: '区域试验、生产试验……' },
      { key: 'trialGroup', label: '试验组别', type: 'text', hint: '例如 黄淮北片水地组' },
      { key: 'unitName', label: '承试单位', type: 'text', hint: '最好填全称', span: 2 },
      { key: 'location', label: '试验地点', type: 'text', span: 2 },
      { key: 'altitude', label: '海拔', type: 'number', unit: 'm' },
      { key: 'lat', label: '北纬', type: 'text' },
      { key: 'lng', label: '东经', type: 'text' },
      { key: 'leader', label: '负责人', type: 'text', hint: '可以不填' },
      { key: 'recorder', label: '记载人', type: 'text', hint: '必须填写' },
    ],
  },
  {
    key: 'design',
    title: '一、试验设计',
    fields: [
      { key: 'varietyCount', label: '供试品种数', type: 'number', unit: '个' },
      { key: 'commonCK', label: '共同对照品种', type: 'text' },
      { key: 'auxCK', label: '辅助对照品种', type: 'text' },
      { key: 'reps', label: '重复次数', type: 'number', unit: '次' },
      { key: 'plotLength', label: '小区长度', type: 'number', unit: 'm', hint: '收获时计产的长度，截头的部分不算' },
      { key: 'plotWidth', label: '小区宽度', type: 'number', unit: 'm', hint: '包括畦埂的宽度' },
      { key: 'rowsPerPlot', label: '每小区行数', type: 'number', unit: '行' },
      { key: 'rowSpacing', label: '行距', type: 'number', unit: 'cm' },
      { key: 'fieldArea', label: '试验田面积', type: 'number', unit: '亩' },
      { key: 'density', label: '密度', type: 'number', unit: '万苗/亩' },
    ],
  },
  {
    key: 'cultivation',
    title: '二、栽培管理',
    fields: [
      { key: 'prevCrop', label: '前茬', type: 'text' },
      { key: 'soil', label: '土质', type: 'text' },
      { key: 'irrigated', label: '水、旱地', type: 'select', options: ['水地', '旱地'] },
      { key: 'sowingMethod', label: '播种方法', type: 'text' },
      { key: 'sowingDate', label: '播种期', type: 'date' },
      { key: 'harvestDate', label: '收获期', type: 'date' },
      area('baseFert', '基肥（种类、数量、施肥时间及方法）'),
      area('tillage', '整地（时间、机具、质量）'),
      area('seedFert', '种肥（种类、数量、施肥时间及方法）'),
      area('seedTreat', '种子处理'),
      area('topDress', '追肥（种类、数量、质量、施用时间及方法）'),
      area('weeding', '中耕除草（时间、次数、方法和质量）'),
      area('irrigation', '灌溉（时间、次数、方法）'),
      area('pest', '病虫害防治（对象、时间、药剂名称和方法）'),
      area('other', '其它'),
    ],
  },
  {
    key: 'yield',
    title: '产量计算参数',
    desc: '记载本：收获面积 = 收获长 ×［(小区行数 − 1) × 行距 + 畦埂宽］。小区行数、行距用上面"试验设计"里填的。填好后折合亩产按收获面积算。',
    fields: [
      { key: 'harvestLength', label: '收获长度', type: 'number', unit: 'm', hint: '不填时用小区长度' },
      { key: 'plannedWidth', label: '规划小区宽', type: 'number', unit: 'm' },
      { key: 'ridgeWidth', label: '畦埂宽', type: 'number', unit: 'cm' },
      { key: 'harvestArea', label: '收获面积（直接填）', type: 'number', unit: 'm²', hint: '填了就按它算，不再用上面的公式' },
    ],
  },
  {
    key: 'contact',
    title: '联系信息',
    fields: [
      { key: 'testUnit', label: '试验单位', type: 'text', span: 2 },
      { key: 'contact', label: '负责人', type: 'text' },
      { key: 'phone', label: '联系电话', type: 'text' },
    ],
  },
]

export const BOOK_FIELDS: BookField[] = BOOK_GROUPS.flatMap((g) => g.fields)

/** 品种评价页的气象分析，和服务器一起存在 info 里 */
export const WEATHER_ANALYSIS_KEY = 'weatherAnalysis'

const num = (v: string | undefined): number | null => {
  if (v == null || v.trim() === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 收获面积（m²），和服务器 HarvestArea 的规则一样：直接填了用填的；
 * 否则 收获长（没填用小区长度）×［(行数 − 1) × 行距 + 畦埂宽］÷ 100。算不出来返回 null
 */
export function harvestArea(info: Record<string, string>): number | null {
  const manual = num(info.harvestArea)
  if (manual != null && manual > 0) return round2(manual)
  const length = num(info.harvestLength) ?? num(info.plotLength)
  const rows = num(info.rowsPerPlot)
  const spacing = num(info.rowSpacing)
  const ridge = num(info.ridgeWidth)
  if (length == null || rows == null || spacing == null || ridge == null) return null
  if (length <= 0 || rows <= 0 || spacing <= 0 || ridge < 0) return null
  const widthCm = (rows - 1) * spacing + ridge
  return widthCm > 0 ? round2((length * widthCm) / 100) : null
}

const round2 = (n: number) => Math.round(n * 100) / 100

// ---------- 气象资料 ----------

export interface WeatherRow {
  key: string
  /** 左边的分组，例如"上旬气温" */
  group: string
  label: string
  unit: string
  /** 由别的行算出来的：没填时显示算出的值 */
  derive?: { from: string[]; how: 'avg' | 'sum' }
}

export const WEATHER_MONTHS = [9, 10, 11, 12, 1, 2, 3, 4, 5, 6]

const DEKADS = ['上旬', '中旬', '下旬']

export const WEATHER_ROWS: WeatherRow[] = [
  ...DEKADS.flatMap((d, i) => [
    { key: `t${i}_avg`, group: `${d}气温`, label: '平均气温', unit: '℃' },
    { key: `t${i}_max`, group: `${d}气温`, label: '最高气温', unit: '℃' },
    { key: `t${i}_min`, group: `${d}气温`, label: '最低气温', unit: '℃' },
  ]),
  { key: 'tMonth', group: '月平均', label: '月平均气温', unit: '℃', derive: { from: ['t0_avg', 't1_avg', 't2_avg'], how: 'avg' } },
  ...DEKADS.map((d, i) => ({ key: `p${i}`, group: '降水', label: d, unit: 'mm' })),
  { key: 'pMonth', group: '月降水', label: '月降水总量', unit: 'mm', derive: { from: ['p0', 'p1', 'p2'], how: 'sum' } },
  { key: 'pDays', group: '月降水', label: '月降水天数', unit: '天' },
  { key: 'pMax', group: '月降水', label: '月最大降水量', unit: 'mm' },
  ...DEKADS.map((d, i) => ({ key: `s${i}`, group: '日照', label: d, unit: '小时' })),
  { key: 'sMonth', group: '月日照', label: '月日照时数', unit: '小时', derive: { from: ['s0', 's1', 's2'], how: 'sum' } },
]

export type WeatherKind = 'cur' | 'norm'

export const weatherKey = (row: string, month: number, kind: WeatherKind) => `${row}|${month}|${kind}`

/** 没填的汇总行（月平均气温、月降水总量、月日照时数）按旬的值算出来，三旬都填了才算 */
export function derivedWeather(weather: Record<string, string>, row: WeatherRow, month: number, kind: WeatherKind): number | null {
  if (!row.derive) return null
  const vals = row.derive.from.map((k) => num(weather[weatherKey(k, month, kind)]))
  if (vals.some((v) => v == null)) return null
  const sum = (vals as number[]).reduce((a, b) => a + b, 0)
  return Math.round((row.derive.how === 'avg' ? sum / vals.length : sum) * 10) / 10
}

// ---------- 第一次打开时按已有资料预先填好 ----------

/** 出现最多的值 */
function mode<T>(values: (T | null | undefined)[]): T | null {
  const counts = new Map<T, number>()
  for (const v of values) {
    if (v != null) counts.set(v, (counts.get(v) ?? 0) + 1)
  }
  let best: T | null = null
  let n = 0
  for (const [v, c] of counts) {
    if (c > n) {
      best = v
      n = c
    }
  }
  return best
}

const str = (v: unknown) => (v == null || v === '' ? undefined : String(v))

/**
 * 记载本里和实验田、试验、小区重复的项目，按已有资料算出来：
 * 年度、试验区别、地点、经纬度、海拔、前茬、土质、水旱地、试验田面积、品种数、对照、重复次数、小区尺寸
 */
export function prefillFromSetup(
  field: Field | null,
  trial: Trial | null,
  plots: Plot[],
  layout: Layout | null,
  varietyById: Map<number, Variety>,
): Record<string, string> {
  const out: Record<string, string | undefined> = {}
  if (trial) {
    out.year = `${trial.seasonYear - 1}-${trial.seasonYear}`
    out.trialType = TRIAL_TYPE[trial.trialType]
    out.reps = str(trial.replicateCount)
  }
  if (field) {
    out.location = str(field.location)
    out.lat = str(field.latitude)
    out.lng = str(field.longitude)
    out.altitude = str(field.altitudeM)
    out.prevCrop = str(field.previousCrop)
    out.soil = str(field.soilType)
    out.irrigated = field.irrigated == null ? undefined : field.irrigated ? '水地' : '旱地'
    out.fieldArea = str(field.areaMu)
  }
  const trialPlots = plots.filter((p) => p.kind === 'PLOT')
  out.plotLength = str(mode(trialPlots.map((p) => p.lengthM)))
  out.plotWidth = str(mode(trialPlots.map((p) => p.widthM)))
  out.rowsPerPlot = str(mode(trialPlots.map((p) => p.plantingRows)))
  out.rowSpacing = str(mode(trialPlots.map((p) => p.rowSpacingCm)))
  if (layout) {
    const ids = [...new Set(layout.plantings.map((p) => p.varietyId).filter((v): v is number => v != null))]
    if (ids.length) out.varietyCount = String(ids.length)
    const cks = ids.map((id) => varietyById.get(id)).filter((v) => v?.controlType).map((v) => v!.name)
    if (cks.length) out.commonCK = cks.join('、')
    if (!out.reps) {
      const reps = new Set(layout.plantings.map((p) => p.replicateNo).filter((r) => r != null))
      if (reps.size) out.reps = String(reps.size)
    }
  }
  return Object.fromEntries(Object.entries(out).filter((e): e is [string, string] => e[1] != null))
}

/** 已经填了的不动，空着的用预填值 */
export function mergePrefill(info: Record<string, string>, prefill: Record<string, string>): Record<string, string> {
  const out = { ...info }
  for (const [k, v] of Object.entries(prefill)) {
    if (!out[k]?.trim()) out[k] = v
  }
  return out
}
