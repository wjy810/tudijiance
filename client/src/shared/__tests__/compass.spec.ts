import { describe, expect, it } from 'vitest'

import { bearingLabel, eastNorth, eastNorthText, edgeLabels, northOnScreen, northUpRotation } from '@/shared/compass'

describe('compass', () => {
  it('labels the four edges', () => {
    expect(edgeLabels(0, 0)).toEqual({ top: '北', right: '东', bottom: '南', left: '西' })
    // 北在图纸右边
    expect(edgeLabels(90, 0)).toEqual({ top: '西', right: '北', bottom: '东', left: '南' })
    // 画面再转 −90°，北又朝上了
    expect(edgeLabels(90, -90)).toEqual({ top: '北', right: '东', bottom: '南', left: '西' })
    expect(edgeLabels(45, 0).top).toBe('西北')
  })

  it('works out north on screen and how to put it up', () => {
    expect(northOnScreen(90, 30)).toBe(120)
    expect(northUpRotation(90)).toBe(270)
    expect(bearingLabel(-10)).toBe('北')
    expect(bearingLabel(200)).toBe('南')
  })

  it('measures east and north from the field center', () => {
    const c = { x: 10, y: 10 }
    // 北朝上：往右是东，往上是北
    expect(eastNorth({ x: 13, y: 6 }, c, 0, 1)).toEqual({ east: 3, north: 4 })
    // 北在右边：往右是北，往下是东
    const v = eastNorth({ x: 13, y: 12 }, c, 90, 1)
    expect(v.north).toBeCloseTo(3)
    expect(v.east).toBeCloseTo(2)
    expect(eastNorthText({ east: -2.04, north: 0.01 })).toBe('西 2 m · 北 0 m')
  })
})
