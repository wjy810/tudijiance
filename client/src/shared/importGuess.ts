// 导入历史表格：按表头猜每一列对应什么。桌面端和以后的手机端共用
import type { TraitView } from '@/shared/api'

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

export function guessTarget(header: string, traits: TraitView[]): ColumnTarget {
  const h = normalizeHeader(header)
  if (!h) {
    return { kind: 'ignore' }
  }
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

/** 猜表头在第几行（从 1 开始）：前 10 行里第一个含有"品种""小区""编号""重复"的行，找不到时用第一个有 3 个以上格子的行 */
export function guessHeaderRow(rows: string[][]): number {
  const limit = Math.min(rows.length, 10)
  for (let i = 0; i < limit; i++) {
    const cells = rows[i]!.map(normalizeHeader).filter(Boolean)
    if (cells.length >= 2 && cells.some((c) => KEY_COLUMNS.slice(0, 3).some((k) => k.names.includes(c)))) {
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
