import { describe, expect, it } from 'vitest'

import { rangeText, rangeWarning } from '@/shared/quality'

describe('rangeWarning', () => {
  const height = { minValue: 30, maxValue: 150, unit: 'cm' }

  it('warns outside the usual range only', () => {
    expect(rangeWarning(height, 78)).toBeNull()
    expect(rangeWarning(height, 12)).toBe('偏低：常见范围 30–150 cm，确认没录错？')
    expect(rangeWarning(height, 210)).toContain('偏高')
    expect(rangeWarning(height, null)).toBeNull()
    expect(rangeWarning({ minValue: null, maxValue: null, unit: 'cm' }, 1000)).toBeNull()
  })

  it('writes one-sided ranges and hides the grade unit', () => {
    expect(rangeText({ minValue: 0, maxValue: null, unit: '%' })).toBe('≥ 0 %')
    expect(rangeText({ minValue: null, maxValue: 5, unit: '级' })).toBe('≤ 5')
  })
})

describe('DEFAULT_TRAIT_RANGES', () => {
  it('matches the ranges seeded by the server migration', async () => {
    const { readFileSync } = await import('node:fs')
    const { resolve } = await import('node:path')
    const { DEFAULT_TRAIT_RANGES } = await import('@/shared/quality')
    const sql = readFileSync(resolve(process.cwd(), '../server/src/main/resources/db/migration/V10__data_quality.sql'), 'utf8')
    const fromSql: Record<string, [number, number]> = {}
    for (const m of sql.matchAll(/min_value = (\d+), max_value = (\d+) WHERE code (?:= '(\w+)'|IN \(([^)]*)\))/g)) {
      const codes = m[3] ? [m[3]] : [...(m[4] ?? '').matchAll(/'(\w+)'/g)].map((c) => c[1] ?? '')
      for (const c of codes) fromSql[c] = [Number(m[1]), Number(m[2])]
    }
    expect(Object.keys(fromSql).length).toBeGreaterThan(20)
    expect(Object.fromEntries(Object.entries(DEFAULT_TRAIT_RANGES).map(([k, v]) => [k, [...v]]))).toEqual(fromSql)
  })
})
