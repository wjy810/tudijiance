import { describe, expect, it } from 'vitest'

import type { LineDetail, LineRow, TraitValueRow } from '@/shared/api'
import { axisOf, buildCards, byGroup, shortSeason } from '@/shared/lineCards'

function row(traitId: number, name: string, values: (number | string)[], extra: Partial<TraitValueRow> = {}): TraitValueRow {
  const nums = values.filter((v): v is number => typeof v === 'number')
  return {
    traitId, traitName: name, category: 'MORPHOLOGY', sortOrder: traitId, important: true,
    average: nums.length ? nums.reduce((a, b) => a + b, 0) / nums.length : null,
    display: values.join('、'), count: values.length, dataType: nums.length ? 'NUMBER' : 'OPTION', unit: 'cm',
    plots: values.map((v, i) => ({
      plotId: i + 1, place: `东地 区块${i < 2 ? 1 : 2} ${i + 1} 号小区块`, display: String(v),
      value: typeof v === 'number' ? v : null, group: `东地 区块${i < 2 ? 1 : 2}`, plotCode: String(i + 1),
    })),
    ...extra,
  }
}

function detail(id: number, seasons: { id: number; name: string; traits: TraitValueRow[] }[]): LineDetail {
  return {
    line: { id, name: `品系${id}` } as LineRow, originText: '', parents: [], children: [], parentPlaces: [], plantings: [],
    plantValues: [],
    seasonValues: seasons.map((s) => ({ seasonId: s.id, seasonName: s.name, generation: null, traits: s.traits })),
  }
}

describe('lineCards', () => {
  it('shortens season names', () => {
    expect(shortSeason('2024-2025 年度大田')).toBe('24-25 大田')
    expect(shortSeason('2025 夏繁')).toBe('25 夏繁')
  })

  it('builds a numeric card with range, change from last season and comparison', () => {
    const d = detail(1, [
      { id: 1, name: '2024-2025 年度大田', traits: [row(10, '株高', [70, 74])] },
      { id: 2, name: '2025-2026 年度大田', traits: [row(10, '株高', [76, 80, 78, 82]), row(11, '穗型', ['纺锤', '纺锤', '长方'])] },
    ])
    const parent = detail(9, [{ id: 2, name: '2025-2026 年度大田', traits: [row(10, '株高', [75])] }])
    const cards = buildCards(d, [{ detail: parent, role: '母本' }], null)
    const h = cards.find((c) => c.traitId === 10)!
    expect(h.kind).toBe('number')
    expect(h.current?.average).toBe(79)
    expect(h.range).toEqual({ min: 76, max: 82 })
    expect(h.change?.pct).toBe(9.7)
    expect(h.compares[0]).toMatchObject({ role: '母本', sameSeason: true, pct: 5.3 })
    expect(h.seasons.map((s) => s.short)).toEqual(['24-25 大田', '25-26 大田'])
    const ax = axisOf(h)!
    expect(ax.min).toBeLessThan(75)
    expect(ax.max).toBeGreaterThan(82)
    // 选项：每种几个
    const t = cards.find((c) => c.traitId === 11)!
    expect(t.kind).toBe('choice')
    expect(t.counts).toEqual([{ label: '纺锤', count: 2 }, { label: '长方', count: 1 }])
  })

  it('uses the compared line latest season when it was not grown in the same season', () => {
    const d = detail(1, [{ id: 2, name: '2025-2026 年度大田', traits: [row(10, '株高', [80])] }])
    const parent = detail(9, [{ id: 1, name: '2024-2025 年度大田', traits: [row(10, '株高', [64])] }])
    const c = buildCards(d, [{ detail: parent, role: '父本' }], 2)[0]!
    expect(c.compares[0]).toMatchObject({ sameSeason: false, seasonName: '2024-2025 年度大田', pct: 25 })
  })

  it('groups positions by block', () => {
    expect(byGroup(row(10, '株高', [70, 72, 74]))).toEqual([
      { group: '东地 区块1', items: [{ code: '1 号', display: '70' }, { code: '2 号', display: '72' }] },
      { group: '东地 区块2', items: [{ code: '3 号', display: '74' }] },
    ])
  })
})
