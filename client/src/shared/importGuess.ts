// 导入历史表格：猜数据在表格里的哪一块、是按列还是按行排的、每个字段对应什么。桌面端和以后的手机端共用
import type { ImportOrientation, ImportRange, TraitView } from '@/shared/api'

export type ColumnTarget =
  | { kind: 'ignore' }
  | { kind: 'plot' }
  | { kind: 'variety' }
  | { kind: 'replicate' }
  | { kind: 'date' }
  | { kind: 'trait'; traitId: number }

/** 去掉空格、括号里的单位和结尾的冒号：" 株高（cm）：" → "株高" */
export function normalizeHeader(header: string): string {
  return header
    .replace(/[（(【[][^）)】\]]*[）)】\]]/g, '')
    .replace(/[\s:：]/g, '')
    .trim()
}

/**
 * 表头别名的规范化写法：全角转半角（NFKC）、去掉空白、英文字母小写。
 * 和服务器 AliasText.normalize 的规则一样，用来和录入项目的别名比较。
 */
export function aliasKey(header: string): string {
  return header.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
}

/** 表头括号里的单位："株高（cm）" → "cm" */
export function unitOf(header: string): string | null {
  const m = header.match(/[（(【[]([^）)】\]]{1,12})[）)】\]]/)
  return m ? m[1]!.trim() : null
}

const KEY_COLUMNS: { kind: 'plot' | 'variety' | 'replicate' | 'date'; names: string[] }[] = [
  { kind: 'plot', names: ['小区编号', '小区号', '小区', '编号', '区号', '小区代号'] },
  { kind: 'variety', names: ['品种', '品种名称', '品种名', '品系', '品种系', '参试品种'] },
  { kind: 'replicate', names: ['重复', '重复号', '区组'] },
  { kind: 'date', names: ['调查日期', '记载日期', '日期'] },
]

/** 表格里常见的叫法 → 项目编码 */
const TRAIT_SYNONYMS: Record<string, string> = {
  播期: 'sowing_date',
  播种期: 'sowing_date',
  出苗期: 'emergence_date',
  抽穗期: 'heading_date',
  扬花期: 'flowering_date',
  开花期: 'flowering_date',
  成熟期: 'maturity_date',
  株高: 'plant_height',
  基本苗: 'basic_seedlings',
  最高茎蘖: 'max_tillers',
  最高茎蘖数: 'max_tillers',
  最高分蘖: 'max_tillers',
  有效穗: 'effective_spikes',
  有效穗数: 'effective_spikes',
  穗数: 'effective_spikes',
  亩穗数: 'effective_spikes',
  穗粒数: 'grains_per_spike',
  每穗粒数: 'grains_per_spike',
  千粒重: 'thousand_grain_weight',
  容重: 'test_weight',
  小区产量: 'plot_yield',
  小区产: 'plot_yield',
  亩产: 'yield_per_mu',
  亩产量: 'yield_per_mu',
  折合亩产: 'yield_per_mu',
  倒伏: 'lodging_final_grade',
  倒伏程度: 'lodging_final_grade',
}

/** 能导入的项目：录入的项目，加上折合亩产（按小区面积换算成小区产量） */
export function importable(t: TraitView): boolean {
  return !t.trait.isComputed || t.trait.code === 'yield_per_mu'
}

/**
 * 按表头猜字段对应什么：先看录入项目里记下的表头别名（老师确认过的），
 * 再看小区编号、品种、重复、日期这几个固定叫法，最后按项目名称和常见叫法。
 */
export function guessTarget(header: string, traits: TraitView[]): ColumnTarget {
  const key = aliasKey(header)
  if (!key) {
    return { kind: 'ignore' }
  }
  const byAlias = traits.find((t) => importable(t) && t.aliases?.includes(key))
  if (byAlias) {
    return { kind: 'trait', traitId: byAlias.trait.id }
  }
  const h = normalizeHeader(header)
  for (const k of KEY_COLUMNS) {
    if (k.names.includes(h)) {
      return { kind: k.kind }
    }
  }
  const byName = traits.find((t) => importable(t) && normalizeHeader(t.trait.name) === h)
  if (byName) {
    return { kind: 'trait', traitId: byName.trait.id }
  }
  const code = TRAIT_SYNONYMS[h]
  const bySynonym = code ? traits.find((t) => t.trait.code === code) : undefined
  return bySynonym ? { kind: 'trait', traitId: bySynonym.trait.id } : { kind: 'ignore' }
}

const isKeyName = (cell: string) => KEY_COLUMNS.slice(0, 3).some((k) => k.names.includes(normalizeHeader(cell)))
const filled = (cell: string | undefined) => !!cell && cell.trim() !== ''

/** 猜表头在第几行（从 1 开始）：前 10 行里第一个含有"品种""小区""编号""重复"的行，找不到时用第一个有 3 个以上格子的行 */
export function guessHeaderRow(rows: string[][]): number {
  const limit = Math.min(rows.length, 10)
  for (let i = 0; i < limit; i++) {
    const cells = rows[i]!.map(normalizeHeader).filter(Boolean)
    if (cells.length >= 2 && rows[i]!.some(isKeyName)) {
      return i + 1
    }
  }
  for (let i = 0; i < limit; i++) {
    if (rows[i]!.filter((c) => c.trim()).length >= 3) {
      return i + 1
    }
  }
  return 1
}

/**
 * 猜数据的方向：比较"品种""小区""重复"这样的字段名出现在哪儿——
 * 集中在某一行里是每列一个字段（按列），集中在第一列里是每行一个字段（按行）；一样多时按列。
 */
export function guessOrientation(rows: string[][]): ImportOrientation {
  const top = rows.slice(0, 10)
  const rowKeys = Math.max(0, ...top.map((r) => r.filter(isKeyName).length))
  const sample = rows.slice(0, 40)
  const firstCol = Math.min(...sample.map((r) => {
    const i = r.findIndex(filled)
    return i < 0 ? Number.MAX_SAFE_INTEGER : i
  }))
  const colKeys = Number.isFinite(firstCol) ? sample.filter((r) => isKeyName(r[firstCol] ?? '')).length : 0
  return colKeys > rowKeys ? 'ROWS' : 'COLUMNS'
}

/** 猜数据所在的区域：从表头开始，到最后一个有内容的行和列 */
export function guessRange(rows: string[][], orientation: ImportOrientation): ImportRange {
  let bottom = rows.length - 1
  while (bottom > 0 && !rows[bottom]!.some(filled)) {
    bottom--
  }
  const width = Math.max(1, ...rows.map((r) => r.length))
  if (orientation === 'COLUMNS') {
    const top = guessHeaderRow(rows) - 1
    const header = rows[top] ?? []
    let left = header.findIndex(filled)
    left = left < 0 ? 0 : left
    let right = header.length - 1
    while (right > left && !filled(header[right])) {
      right--
    }
    return { top, left, bottom: Math.max(bottom, top + 1), right: Math.max(right, left) }
  }
  // 按行：表头在第一个有内容的列
  let left = width
  let top = -1
  rows.forEach((r, i) => {
    const c = r.findIndex(filled)
    if (c >= 0) {
      left = Math.min(left, c)
      if (top < 0) top = i
    }
  })
  left = left === width ? 0 : left
  return { top: Math.max(top, 0), left, bottom, right: Math.max(width - 1, left + 1) }
}

export interface FieldPreview {
  /** 区域里的第几个字段，从 0 开始 */
  index: number
  header: string
  /** 前几个值，猜类型、给老师看 */
  samples: string[]
}

/** 区域里的各个字段：按列时是各列，按行时是各行 */
export function fieldsOf(rows: string[][], range: ImportRange, orientation: ImportOrientation, sampleCount = 6): FieldPreview[] {
  const cell = (r: number, c: number) => rows[r]?.[c] ?? ''
  const list: FieldPreview[] = []
  if (orientation === 'COLUMNS') {
    for (let c = range.left; c <= range.right; c++) {
      const samples: string[] = []
      for (let r = range.top + 1; r <= range.bottom && samples.length < sampleCount; r++) {
        if (filled(cell(r, c))) samples.push(cell(r, c))
      }
      list.push({ index: c - range.left, header: cell(range.top, c).trim(), samples })
    }
  } else {
    for (let r = range.top; r <= range.bottom; r++) {
      const samples: string[] = []
      for (let c = range.left + 1; c <= range.right && samples.length < sampleCount; c++) {
        if (filled(cell(r, c))) samples.push(cell(r, c))
      }
      list.push({ index: r - range.top, header: cell(r, range.left).trim(), samples })
    }
  }
  return list
}

export interface CustomTraitSuggestion {
  name: string
  dataType: 'DATE' | 'NUMBER' | 'OPTION' | 'TEXT'
  unit: string | null
  precisionDigits: number | null
  options: string[]
}

const NUMBER_RE = /^\s*-?\d+(?:\.\d+)?\s*[^\d.]{0,6}$/
const DATE_RE = /^(\d{4}\s*[-/.年]\s*)?\d{1,2}\s*[-/.月]\s*\d{1,2}\s*日?$/
/** 一看就是日期的写法：带年、月、日、横杠、斜杠，或者以四位年份开头 */
const CLEAR_DATE_RE = /[年月日\-/]|^\d{4}/

/**
 * 表格里有、录入项目里没有的字段，按表头和值建议怎么新建自定义项目：
 * 像日期 → 日期（"10.12" 这种只有表头像"××期"时才算日期，否则 7.8 这样的小数会被当成日期）；
 * 大多数是数字 → 数值（小数位数按值里最多的）；不同的值不超过 8 个且有重复 → 选项；其它 → 文字。
 */
export function suggestCustomTrait(header: string, values: string[]): CustomTraitSuggestion {
  const name = normalizeHeader(header) || header.trim()
  const unit = unitOf(header)
  const vals = values.map((v) => v.trim()).filter(Boolean)
  const base = { name, unit, precisionDigits: null, options: [] as string[] }
  if (vals.length === 0) {
    return { ...base, dataType: 'TEXT' }
  }
  const share = (test: (v: string) => boolean) => vals.filter(test).length / vals.length
  const looksLikeStage = /期$/.test(name)
  if (share((v) => DATE_RE.test(v) && (looksLikeStage || CLEAR_DATE_RE.test(v))) >= 0.8) {
    return { ...base, dataType: 'DATE', unit: null }
  }
  if (share((v) => NUMBER_RE.test(v)) >= 0.8) {
    const digits = Math.max(0, ...vals.map((v) => (v.match(/\.(\d+)/)?.[1]?.length ?? 0)))
    return { ...base, dataType: 'NUMBER', precisionDigits: Math.min(digits, 4) }
  }
  const distinct = [...new Set(vals)]
  if (distinct.length >= 2 && distinct.length <= 8 && distinct.length < vals.length && distinct.every((d) => d.length <= 16)) {
    return { ...base, dataType: 'OPTION', options: distinct, unit: null }
  }
  return { ...base, dataType: 'TEXT', unit: null }
}

/** 列号 → Excel 列字母：0 → A，26 → AA */
export function columnLetter(index: number): string {
  let n = index + 1
  let s = ''
  while (n > 0) {
    const r = (n - 1) % 26
    s = String.fromCharCode(65 + r) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

/** 区域写成 Excel 的样子：B2:H30 */
export function rangeText(range: ImportRange): string {
  return `${columnLetter(range.left)}${range.top + 1}:${columnLetter(range.right)}${range.bottom + 1}`
}
