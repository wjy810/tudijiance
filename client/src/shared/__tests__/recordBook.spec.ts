import { describe, expect, it } from 'vitest'

import type { Field, Layout, Plot, Trial, Variety } from '@/shared/api'
import {
  BOOK_FIELDS, WEATHER_ROWS, derivedWeather, harvestArea, mergePrefill, prefillFromSetup, weatherKey,
} from '@/shared/recordBook'

describe('harvestArea（记载本的计产面积）', () => {
  it('uses 收获长 × [(行数 − 1) × 行距 + 畦埂宽]', () => {
    expect(harvestArea({ harvestLength: '6.65', rowsPerPlot: '8', rowSpacing: '20', ridgeWidth: '40' })).toBe(11.97)
  })

  it('falls back to the plot length and prefers a typed-in area', () => {
    expect(harvestArea({ plotLength: '10', rowsPerPlot: '6', rowSpacing: '25', ridgeWidth: '50' })).toBe(17.5)
    expect(harvestArea({ harvestArea: '13.34', harvestLength: '10' })).toBe(13.34)
  })

  it('returns null when parameters are missing or invalid', () => {
    expect(harvestArea({ harvestLength: '10', rowsPerPlot: '6' })).toBeNull()
    expect(harvestArea({ harvestLength: 'x', rowsPerPlot: '6', rowSpacing: '20', ridgeWidth: '0' })).toBeNull()
    expect(harvestArea({ harvestLength: '10', rowsPerPlot: '1', rowSpacing: '20', ridgeWidth: '0' })).toBeNull()
  })
})

describe('weather', () => {
  const tMonth = WEATHER_ROWS.find((r) => r.key === 'tMonth')!
  const pMonth = WEATHER_ROWS.find((r) => r.key === 'pMonth')!

  it('derives monthly mean temperature and total rain from the three dekads', () => {
    const w = {
      [weatherKey('t0_avg', 10, 'cur')]: '18', [weatherKey('t1_avg', 10, 'cur')]: '16', [weatherKey('t2_avg', 10, 'cur')]: '14.5',
      [weatherKey('p0', 10, 'cur')]: '12.5', [weatherKey('p1', 10, 'cur')]: '0', [weatherKey('p2', 10, 'cur')]: '30',
    }
    expect(derivedWeather(w, tMonth, 10, 'cur')).toBe(16.2)
    expect(derivedWeather(w, pMonth, 10, 'cur')).toBe(42.5)
    // 常年没填，算不出来
    expect(derivedWeather(w, tMonth, 10, 'norm')).toBeNull()
  })

  it('has the rows of the record book', () => {
    expect(WEATHER_ROWS).toHaveLength(20)
  })
})

describe('prefillFromSetup', () => {
  const field = {
    id: 1, name: '东区', location: '郑州', latitude: 34.75, longitude: 113.62, altitudeM: 110, areaMu: 2.5,
    rowCount: 2, colCount: 2, soilType: '壤土', irrigated: true, previousCrop: '玉米', remark: null, createdAt: '', updatedAt: '',
  } satisfies Field
  const trial = {
    id: 9, fieldId: 1, seasonYear: 2025, name: '区试', trialType: 'REGIONAL', wheatZone: 'Q3', replicateCount: null,
    remark: null, createdAt: '',
  } satisfies Trial
  const plot = (id: number, kind: Plot['kind'] = 'PLOT'): Plot => ({
    id, fieldId: 1, rowNo: 1, colNo: id, code: String(id), kind, lengthM: 6.65, widthM: 2, plantingRows: 8, rowSpacingCm: 20,
  })
  const layout: Layout = {
    trial,
    plantings: [
      { plotId: 1, varietyId: 11, replicateNo: 1 }, { plotId: 2, varietyId: 12, replicateNo: 1 },
      { plotId: 3, varietyId: 11, replicateNo: 2 },
    ],
    links: [],
  }
  const varieties = new Map<number, Variety>([
    [11, { id: 11, name: '周麦18', controlType: 'YIELD' } as Variety],
    [12, { id: 12, name: '新品系A', controlType: null } as Variety],
  ])

  it('fills what the system already knows', () => {
    const p = prefillFromSetup(field, trial, [plot(1), plot(2), plot(3), plot(4, 'GUARD')], layout, varieties)
    expect(p).toMatchObject({
      year: '2024-2025', trialType: '区域试验', location: '郑州', altitude: '110', irrigated: '水地', prevCrop: '玉米',
      varietyCount: '2', commonCK: '周麦18', reps: '2', plotLength: '6.65', rowsPerPlot: '8', rowSpacing: '20',
    })
    // 只用记载本里有的字段
    const keys = new Set(BOOK_FIELDS.map((f) => f.key))
    expect(Object.keys(p).every((k) => keys.has(k))).toBe(true)
  })

  it('does not overwrite what the teacher typed', () => {
    expect(mergePrefill({ location: '新乡', recorder: '' }, { location: '郑州', recorder: '张三' }))
      .toEqual({ location: '新乡', recorder: '张三' })
  })
})
