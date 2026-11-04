import { describe, expect, it } from 'vitest'

import type { TraitView } from '@/shared/api'
import {
  aliasKey, columnLetter, fieldsOf, guessHeaderRow, guessOrientation, guessRange, guessTarget, guessTargets, normalizeHeader,
  rangeText,
  suggestCustomTrait, unitOf,
} from '@/shared/importGuess'

function trait(id: number, code: string, name: string, isComputed = false, aliases: string[] = []): TraitView {
  return {
    trait: {
      id, code, name, category: 'YIELD', dataType: 'NUMBER', unit: null, precisionDigits: 1, aggMethod: 'AVG',
      surveyTime: null, stdClause: null, isStandard: true, isComputed, enabled: true, important: false, sortOrder: id, description: null,
    },
    options: [],
    aliases,
  }
}

const traits = [
  trait(1, 'plant_height', '株高'),
  trait(2, 'plot_yield', '小区产量'),
  trait(3, 'yield_per_mu', '折合亩产', true),
  trait(4, 'whole_growth_days', '全生育期', true),
  trait(5, 'effective_spikes', '有效穗数'),
  trait(6, 'custom_spike_len', '穗长', false, ['穗长(cm)', '穗子长度']),
]

describe('normalizeHeader', () => {
  it('drops units in brackets, spaces and colons', () => {
    expect(normalizeHeader(' 株高（cm）：')).toBe('株高')
    expect(normalizeHeader('小区产量(kg)')).toBe('小区产量')
    expect(normalizeHeader('穗数【万/亩】')).toBe('穗数')
  })
})

describe('guessTarget', () => {
  it('recognises key columns', () => {
    expect(guessTarget('小区号', traits)).toEqual({ kind: 'plot' })
    expect(guessTarget('品种名称', traits)).toEqual({ kind: 'variety' })
    expect(guessTarget('重复', traits)).toEqual({ kind: 'replicate' })
    expect(guessTarget('调查日期', traits)).toEqual({ kind: 'date' })
  })

  it('matches trait names and common synonyms', () => {
    expect(guessTarget('株高(cm)', traits)).toEqual({ kind: 'trait', traitId: 1 })
    expect(guessTarget('亩产（kg）', traits)).toEqual({ kind: 'trait', traitId: 3 })
    expect(guessTarget('穗数', traits)).toEqual({ kind: 'trait', traitId: 5 })
  })

  it('prefers aliases remembered in the trait catalog', () => {
    expect(guessTarget('穗长（CM）', traits)).toEqual({ kind: 'trait', traitId: 6 })
    expect(guessTarget(' 穗子 长度 ', traits)).toEqual({ kind: 'trait', traitId: 6 })
  })

  it('does not map computed traits other than yield per mu', () => {
    expect(guessTarget('全生育期', traits)).toEqual({ kind: 'ignore' })
    expect(guessTarget('备注', traits)).toEqual({ kind: 'ignore' })
  })
})

describe('guessHeaderRow', () => {
  it('skips a title row', () => {
    expect(guessHeaderRow([['2025 年品比试验记载表'], ['小区号', '品种', '株高'], ['101', '济麦22', '78']])).toBe(2)
  })

  it('falls back to the first wide row', () => {
    expect(guessHeaderRow([['说明'], ['a', 'b', 'c']])).toBe(2)
  })
})

describe('columnLetter', () => {
  it('converts like Excel', () => {
    expect(columnLetter(0)).toBe('A')
    expect(columnLetter(25)).toBe('Z')
    expect(columnLetter(26)).toBe('AA')
  })
})

describe('aliasKey and unitOf', () => {
  it('normalizes like the server: full width to half width, no spaces, lower case', () => {
    expect(aliasKey(' 株高 （CM） ')).toBe('株高(cm)')
    expect(aliasKey('ＡＢｃ')).toBe('abc')
  })

  it('reads the unit in brackets', () => {
    expect(unitOf('株高（cm）')).toBe('cm')
    expect(unitOf('穗数【万/亩】')).toBe('万/亩')
    expect(unitOf('品种')).toBeNull()
  })
})

describe('orientation and range', () => {
  const byColumns = [
    ['2025 年记载表'],
    ['', '小区号', '品种', '株高'],
    ['', '101', '济麦22', '78'],
    ['', '102', '周麦36', '80'],
    [],
  ]
  const byRows = [
    ['品种', '济麦22', '周麦36', '百农207'],
    ['重复', 'I', 'I', 'I'],
    ['株高', '78', '80', '76'],
  ]

  it('detects column and row oriented tables', () => {
    expect(guessOrientation(byColumns)).toBe('COLUMNS')
    expect(guessOrientation(byRows)).toBe('ROWS')
  })

  it('finds the data range from the header to the last filled row', () => {
    const r = guessRange(byColumns, 'COLUMNS')
    expect(r).toEqual({ top: 1, left: 1, bottom: 3, right: 3 })
    expect(rangeText(r)).toBe('B2:D4')
    expect(guessRange(byRows, 'ROWS')).toEqual({ top: 0, left: 0, bottom: 2, right: 3 })
  })

  it('lists fields with their headers and samples in either direction', () => {
    const cols = fieldsOf(byColumns, { top: 1, left: 1, bottom: 3, right: 3 }, 'COLUMNS')
    expect(cols.map((f) => f.header)).toEqual(['小区号', '品种', '株高'])
    expect(cols[2]!.samples).toEqual(['78', '80'])
    const rows = fieldsOf(byRows, { top: 0, left: 0, bottom: 2, right: 3 }, 'ROWS')
    expect(rows.map((f) => f.header)).toEqual(['品种', '重复', '株高'])
    expect(rows[0]!.samples).toEqual(['济麦22', '周麦36', '百农207'])
  })
})

describe('suggestCustomTrait', () => {
  it('suggests numbers with precision and the unit from the header', () => {
    expect(suggestCustomTrait('穗长（cm）', ['8.5', '9.25', '9'])).toMatchObject({
      name: '穗长', dataType: 'NUMBER', unit: 'cm', precisionDigits: 2,
    })
  })

  it('does not mistake decimals for month.day dates', () => {
    expect(suggestCustomTrait('穗长（cm）', ['7.8', '8.6', '8', '9.2']).dataType).toBe('NUMBER')
    expect(suggestCustomTrait('拔节期', ['3.5', '3.6', '3.8']).dataType).toBe('DATE')
  })

  it('suggests dates, options and text', () => {
    expect(suggestCustomTrait('拔节期', ['3月5日', '2025-03-06']).dataType).toBe('DATE')
    expect(suggestCustomTrait('长势', ['好', '中', '好', '差'])).toMatchObject({ dataType: 'OPTION', options: ['好', '中', '差'] })
    expect(suggestCustomTrait('备注', ['叶片发黄', '有积水']).dataType).toBe('TEXT')
  })
})

describe('guessTargets（河南省记载本的田间记载）', () => {
  const book = [
    trait(10, 'lodging_first_date', '最初倒伏日期', false, ['倒伏日期']),
    trait(11, 'lodging_first_grade', '最初倒伏程度', false, ['倒伏程度']),
    trait(12, 'lodging_final_date', '最终倒伏日期'),
    trait(13, 'lodging_final_grade', '最终倒伏程度'),
    trait(14, 'effective_spikes', '有效穗数', false, ['亩成穗']),
  ]

  it('maps the second lodging columns to the final lodging', () => {
    const got = guessTargets(['区号', '品种编号', '亩成穗', '倒伏日期', '倒伏程度', '倒伏日期', '倒伏程度'], book)
    expect(got).toEqual([
      { kind: 'plot' }, { kind: 'variety' }, { kind: 'trait', traitId: 14 },
      { kind: 'trait', traitId: 10 }, { kind: 'trait', traitId: 11 },
      { kind: 'trait', traitId: 12 }, { kind: 'trait', traitId: 13 },
    ])
  })

  it('ignores other repeated trait columns after the first', () => {
    expect(guessTargets(['亩成穗', '亩成穗'], book)).toEqual([{ kind: 'trait', traitId: 14 }, { kind: 'ignore' }])
  })

  it('recognises the record book variety header', () => {
    expect(guessTarget('品   种 (系)      编  号', book)).toEqual({ kind: 'variety' })
    expect(guessTarget('品种名或代号', book)).toEqual({ kind: 'variety' })
  })
})
