// 品系详情"各季的数据"：每个项目一张卡片。这里把服务器给的各季数据整理成卡片要显示的东西（纯函数，方便测试）
import type { LineDetail, PlantValueView, TraitCategory, TraitValueRow } from '@/shared/api'

export interface CardSeason {
  seasonId: number
  seasonName: string
  /** 短名：2024-2025 年度大田 → 24-25 大田 */
  short: string
  generation: number | null
  row: TraitValueRow | null
}

export interface CardCompare {
  lineId: number
  name: string
  /** 母本、父本、选自、兄弟、子代、对照品种 */
  role: string
  seasonName: string
  /** 和这个品系是同一季种的 */
  sameSeason: boolean
  row: TraitValueRow
  /** 这个品系比它高多少（%），数值项目才有 */
  pct: number | null
}

export interface TraitCard {
  traitId: number
  name: string
  category: TraitCategory | ''
  sortOrder: number
  important: boolean
  /** number 数值和分级、date 日期、choice 选项和文字 */
  kind: 'number' | 'date' | 'choice'
  unit: string
  seasons: CardSeason[]
  /** 选中的这一季的值，这一季没录时为空 */
  current: TraitValueRow | null
  compares: CardCompare[]
  /** 选出时的单株数据 */
  plant: PlantValueView | null
  /** 数值：各位置的最小、最大 */
  range: { min: number; max: number } | null
  /** 选项：每种有几个位置，多的在前 */
  counts: { label: string; count: number }[]
  /** 和上一次有数据的季比（%） */
  change: { pct: number; from: string } | null
}

export interface ComparedLine {
  detail: LineDetail
  role: string
}

export function shortSeason(name: string): string {
  return name.replace(/20(\d\d)/g, '$1').replace(/\s*年度\s*/, ' ').trim()
}

const pctOf = (a: number | null | undefined, b: number | null | undefined) =>
  a != null && b != null && b !== 0 ? Math.round(((a - b) / Math.abs(b)) * 1000) / 10 : null

function kindOf(row: TraitValueRow): TraitCard['kind'] {
  if (row.dataType === 'DATE') return 'date'
  if (row.dataType === 'NUMBER' || row.dataType === 'GRADE' || row.average != null) return 'number'
  return 'choice'
}

/** 选项、文字：每种值有几个位置 */
export function countValues(row: TraitValueRow): { label: string; count: number }[] {
  const m = new Map<string, number>()
  for (const p of row.plots) m.set(p.display, (m.get(p.display) ?? 0) + 1)
  return [...m.entries()].map(([label, count]) => ({ label, count })).sort((a, b) => b.count - a.count)
}

/** 一个季里各位置按区块分组：东地 区块1 → [1 号 82.5, 2 号 80] */
export function byGroup(row: TraitValueRow): { group: string; items: { code: string; display: string }[] }[] {
  const m = new Map<string, { code: string; display: string }[]>()
  for (const p of row.plots) {
    const list = m.get(p.group || '其它') ?? []
    list.push({ code: p.plotCode ? `${p.plotCode} 号` : p.place, display: p.display })
    m.set(p.group || '其它', list)
  }
  return [...m.entries()].map(([group, items]) => ({ group, items }))
}

/**
 * 整理卡片。seasonId 是选中的季（不给时用最后一季）；compared 是拿来对比的品系（带上和这个品系的关系）
 */
export function buildCards(d: LineDetail, compared: ComparedLine[], seasonId: number | null): TraitCard[] {
  const seasons = d.seasonValues
  const pick = seasonId ?? seasons[seasons.length - 1]?.seasonId ?? null
  const pickIndex = seasons.findIndex((s) => s.seasonId === pick)
  const meta = new Map<number, { name: string; category: TraitCategory | ''; sortOrder: number; important: boolean; kind: TraitCard['kind']; unit: string }>()
  for (const s of seasons) {
    for (const t of s.traits) {
      if (!meta.has(t.traitId)) {
        meta.set(t.traitId, {
          name: t.traitName, category: t.category, sortOrder: t.sortOrder, important: t.important, kind: kindOf(t),
          unit: t.unit && t.unit !== '级' ? t.unit : '',
        })
      }
    }
  }
  for (const v of d.plantValues) {
    if (!meta.has(v.traitId)) {
      meta.set(v.traitId, {
        name: v.traitName, category: '', sortOrder: 0, important: true,
        kind: v.valueDate ? 'date' : v.valueNum != null ? 'number' : 'choice', unit: '',
      })
    }
  }
  const cards: TraitCard[] = []
  for (const [traitId, m] of meta) {
    const rowIn = (s: LineDetail['seasonValues'][number]) => s.traits.find((t) => t.traitId === traitId) ?? null
    const cardSeasons: CardSeason[] = seasons.map((s) => ({
      seasonId: s.seasonId, seasonName: s.seasonName, short: shortSeason(s.seasonName), generation: s.generation, row: rowIn(s),
    }))
    const current = pickIndex >= 0 ? cardSeasons[pickIndex]!.row : null
    let change: TraitCard['change'] = null
    if (current?.average != null) {
      for (let i = pickIndex - 1; i >= 0; i--) {
        const prev = cardSeasons[i]!.row
        if (prev?.average != null) {
          const pct = pctOf(current.average, prev.average)
          if (pct != null) change = { pct, from: cardSeasons[i]!.short }
          break
        }
      }
    }
    const compares: CardCompare[] = []
    for (const c of compared) {
      const same = c.detail.seasonValues.find((s) => s.seasonId === pick)
      const sameRow = same ? same.traits.find((t) => t.traitId === traitId) ?? null : null
      let row = sameRow
      let seasonName = same?.seasonName ?? ''
      if (!row) {
        for (let i = c.detail.seasonValues.length - 1; i >= 0; i--) {
          const r = c.detail.seasonValues[i]!.traits.find((t) => t.traitId === traitId)
          if (r) {
            row = r
            seasonName = c.detail.seasonValues[i]!.seasonName
            break
          }
        }
      }
      if (row) {
        compares.push({
          lineId: c.detail.line.id, name: c.detail.line.name, role: c.role, seasonName, sameSeason: !!sameRow, row,
          pct: m.kind === 'number' ? pctOf(current?.average, row.average) : null,
        })
      }
    }
    const nums = (current?.plots ?? []).map((p) => p.value).filter((v): v is number => v != null)
    cards.push({
      traitId, ...m, seasons: cardSeasons, current, compares,
      plant: d.plantValues.find((v) => v.traitId === traitId) ?? null,
      range: nums.length ? { min: Math.min(...nums), max: Math.max(...nums) } : null,
      counts: current && m.kind === 'choice' ? countValues(current) : [],
      change,
    })
  }
  return cards.sort((a, b) => Number(b.important) - Number(a.important) || a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
}

/** 数值卡片里那条横轴的范围：各位置的值、对比品系的平均、单株值都放得下，两头留一点 */
export function axisOf(card: TraitCard): { min: number; max: number } | null {
  const vals: number[] = []
  for (const p of card.current?.plots ?? []) if (p.value != null) vals.push(p.value)
  for (const c of card.compares) if (c.row.average != null) vals.push(c.row.average)
  if (card.plant?.valueNum != null) vals.push(card.plant.valueNum)
  if (vals.length === 0) return null
  let min = Math.min(...vals)
  let max = Math.max(...vals)
  if (max === min) {
    const pad = Math.abs(max) * 0.1 || 1
    min -= pad
    max += pad
  } else {
    const pad = (max - min) * 0.08
    min -= pad
    max += pad
  }
  return { min, max }
}
