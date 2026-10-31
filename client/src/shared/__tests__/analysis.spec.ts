import { describe, expect, it } from 'vitest'

import type { AnalysisResult } from '@/shared/api'
import { buildOption, chartProblem, dimensionRole, emptyScope, toQuery, type ChartConfig } from '@/shared/analysis'
import { LIGHT } from '@/shared/charts'

const config = (over: Partial<ChartConfig>): ChartConfig => ({
  chartType: 'bar', scope: emptyScope(1), dimensions: [{ type: 'VARIETY' }], measures: [{ traitId: 9, agg: 'AVG' }],
  sorted: false, showValues: false, ...over,
})

const byVariety: AnalysisResult = {
  columns: [
    { key: 'd0', label: '品种', unit: null, kind: 'DIMENSION' },
    { key: 'm0', label: '折合亩产 · 平均', unit: 'kg', kind: 'MEASURE' },
  ],
  rows: [['济麦 22', 590.2], ['周麦 36', 612.5], ['百农 207', 601], ['刚播种的品种', null]],
  records: 12,
}

const byYearVariety: AnalysisResult = {
  columns: [
    { key: 'd0', label: '年份', unit: null, kind: 'DIMENSION' },
    { key: 'd1', label: '品种', unit: null, kind: 'DIMENSION' },
    { key: 'm0', label: '折合亩产 · 平均', unit: 'kg', kind: 'MEASURE' },
  ],
  rows: [['2024', '济麦 22', 580], ['2024', '周麦 36', 600], ['2025', '济麦 22', 610], ['2026', '周麦 36', null]],
  records: 9,
}

type Series = { name: string; type: string; data: unknown[] }

describe('chartProblem', () => {
  it('explains what each chart type needs', () => {
    expect(chartProblem(config({ measures: [] }))).toContain('至少选一个指标')
    expect(chartProblem(config({ chartType: 'pie', dimensions: [] }))).toContain('饼图')
    expect(chartProblem(config({ chartType: 'scatter' }))).toContain('两个指标')
    expect(chartProblem(config({ chartType: 'heatmap' }))).toContain('两个分组')
    expect(chartProblem(config({ chartType: 'radar' }))).toContain('三个指标')
    expect(chartProblem(config({ chartType: 'table', dimensions: [] }))).toBeNull()
    expect(chartProblem(config({}))).toBeNull()
  })
})

describe('buildOption', () => {
  it('sorts bars from high to low and skips groups without values', () => {
    const o = buildOption(byVariety, config({ sorted: true }), LIGHT) as { xAxis: { data: string[] }; series: Series[] }
    expect(o.xAxis.data).toEqual(['周麦 36', '百农 207', '济麦 22'])
    expect(o.series[0]!.data).toEqual([612.5, 601, 590.2])
  })

  it('puts the first group on the axis and splits series by the second', () => {
    const o = buildOption(byYearVariety, config({ chartType: 'line', dimensions: [{ type: 'YEAR' }, { type: 'VARIETY' }] }), LIGHT) as {
      xAxis: { data: string[] }; series: Series[]
    }
    // 2026 年没有值，不画
    expect(o.xAxis.data).toEqual(['2024', '2025'])
    expect(o.series.map((s) => s.name)).toEqual(['济麦 22', '周麦 36'])
    expect(o.series[1]!.data).toEqual([600, null])
  })

  it('draws pies from the first group and measure', () => {
    const o = buildOption(byVariety, config({ chartType: 'donut' }), LIGHT) as { series: { data: { name: string; value: number }[] }[] }
    expect(o.series[0]!.data).toHaveLength(3)
    expect(o.series[0]!.data[0]).toEqual({ name: '济麦 22', value: 590.2 })
  })
})

describe('helpers', () => {
  it('names the role of each grouping by chart type', () => {
    expect(dimensionRole('heatmap', 0)).toBe('纵轴')
    expect(dimensionRole('bar', 1)).toBe('颜色')
  })

  it('turns a chart config into a query', () => {
    const q = toQuery(config({ scope: { ...emptyScope(3), years: [2025], plotIds: [7, 8] } }))
    expect(q).toMatchObject({ fieldId: 3, years: [2025], plotIds: [7, 8], dimensions: [{ type: 'VARIETY' }] })
  })
})
