import { describe, expect, it } from 'vitest'

import { assignSlots, formatNumber, formatPercent } from '@/shared/charts'

describe('assignSlots', () => {
  it('gives new ids the lowest free slots in order', () => {
    const slots = assignSlots(new Map(), [7, 3, 9])
    expect([...slots.entries()]).toEqual([[7, 0], [3, 1], [9, 2]])
  })

  it('keeps the colors of ids that stay selected', () => {
    const first = assignSlots(new Map(), [1, 2, 3])
    const second = assignSlots(first, [3, 1])
    expect(second.get(1)).toBe(0)
    expect(second.get(3)).toBe(2)
  })

  it('reuses a slot freed by a removed id', () => {
    const first = assignSlots(new Map(), [1, 2, 3])
    const second = assignSlots(first, [1, 3, 4])
    expect(second.get(4)).toBe(1)
  })
})

describe('format', () => {
  it('trims trailing zeros and groups thousands', () => {
    expect(formatNumber(614.1, 1)).toBe('614.1')
    expect(formatNumber(1200, 1)).toBe('1,200')
    expect(formatNumber(null)).toBe('—')
  })

  it('signs percentages', () => {
    expect(formatPercent(16.7)).toBe('+16.7%')
    expect(formatPercent(-3.04)).toBe('-3.0%')
    expect(formatPercent(null)).toBe('')
  })
})
