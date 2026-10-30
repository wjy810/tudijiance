import { describe, expect, it } from 'vitest'

import type { TraitView } from '@/shared/api'
import { columnLetter, guessHeaderRow, guessTarget, normalizeHeader } from '@/shared/importGuess'

function trait(id: number, code: string, name: string, isComputed = false): TraitView {
  return {
    trait: {
      id, code, name, category: 'YIELD', dataType: 'NUMBER', unit: null, precisionDigits: 1, aggMethod: 'AVG',
      surveyTime: null, stdClause: null, isStandard: true, isComputed, enabled: true, sortOrder: id, description: null,
    },
    options: [],
  }
}

const traits = [
  trait(1, 'plant_height', '株高'),
  trait(2, 'plot_yield', '小区产量'),
  trait(3, 'yield_per_mu', '折合亩产', true),
  trait(4, 'whole_growth_days', '全生育期', true),
  trait(5, 'effective_spikes', '有效穗数'),
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
