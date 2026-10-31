// 自助分析：图表设置、常用模板、设置检查，以及把查询结果画成 ECharts 图。不依赖页面，方便测试
import type { EChartsCoreOption } from 'echarts/core'

import type {
  AnalysisAgg, AnalysisDimension, AnalysisDimensionSpec, AnalysisMeasureSpec, AnalysisQuery, AnalysisResult,
} from '@/shared/api'
import { FONT, MAX_SERIES, axisStyle, formatNumber, tooltipStyle, type ChartTheme } from '@/shared/charts'

export type ChartType = 'bar' | 'hbar' | 'stack' | 'line' | 'area' | 'pie' | 'donut' | 'scatter' | 'radar' | 'heatmap' | 'table'

/** 范围：哪块地、哪些年、哪些品种、哪些重复、哪些小区（空表示全部） */
export interface AnalysisScope {
  fieldId: number | null
  years: number[]
  varietyIds: number[]
  replicates: number[]
  plotIds: number[]
}

/** 保存下来的一张图的全部设置 */
export interface ChartConfig {
  chartType: ChartType
  scope: AnalysisScope
  dimensions: AnalysisDimensionSpec[]
  measures: AnalysisMeasureSpec[]
  /** 柱状图、条形图按数值从大到小排 */
  sorted: boolean
  /** 在图上标出数值 */
  showValues: boolean
}

export const CHART_TYPES: { value: ChartType; label: string; hint: string }[] = [
  { value: 'bar', label: '柱状图', hint: '比较各组的大小' },
  { value: 'hbar', label: '条形图', hint: '组多、名字长时用' },
  { value: 'stack', label: '堆积柱', hint: '看各部分加起来的总量' },
  { value: 'line', label: '折线图', hint: '看历年变化' },
  { value: 'area', label: '面积图', hint: '看变化和量的大小' },
  { value: 'pie', label: '饼图', hint: '看各部分占多少' },
  { value: 'donut', label: '环形图', hint: '饼图的另一种样子' },
  { value: 'scatter', label: '散点图', hint: '看两个指标的关系' },
  { value: 'radar', label: '雷达图', hint: '一个品种的多项性状' },
  { value: 'heatmap', label: '热力图', hint: '两种分组交叉看' },
  { value: 'table', label: '数据表', hint: '只看数字' },
]

export const AGG_LABEL: Record<AnalysisAgg, string> = {
  AVG: '平均', SUM: '合计', MAX: '最大', MIN: '最小', MEDIAN: '中位数', COUNT: '个数', CV: '变异系数',
}

export const DIMENSION_LABEL: Record<Exclude<AnalysisDimension, 'TRAIT'>, string> = {
  VARIETY: '品种', YEAR: '年份', REPLICATE: '重复', PLOT: '小区', FIELD: '实验田',
}

export function emptyScope(fieldId: number | null = null): AnalysisScope {
  return { fieldId, years: [], varietyIds: [], replicates: [], plotIds: [] }
}

export function toQuery(c: ChartConfig): AnalysisQuery {
  return {
    fieldId: c.scope.fieldId,
    years: c.scope.years,
    varietyIds: c.scope.varietyIds,
    replicates: c.scope.replicates,
    plotIds: c.scope.plotIds,
    dimensions: c.dimensions,
    measures: c.measures,
  }
}

/** 常用分析：一点就出图。trait 写项目编码，用的时候换成编号 */
export interface Preset {
  key: string
  title: string
  desc: string
  chartType: ChartType
  dimensions: { type: AnalysisDimension; trait?: string }[]
  measures: { trait?: string; agg: AnalysisAgg }[]
  sorted?: boolean
}

export const PRESETS: Preset[] = [
  {
    key: 'yield-rank', title: '各品种亩产对比', desc: '选中年份各品种折合亩产的平均值，从高到低',
    chartType: 'hbar', dimensions: [{ type: 'VARIETY' }], measures: [{ trait: 'yield_per_mu', agg: 'AVG' }], sorted: true,
  },
  {
    key: 'yield-trend', title: '历年亩产变化', desc: '每个品种每年的折合亩产，看哪些品种稳定',
    chartType: 'line', dimensions: [{ type: 'YEAR' }, { type: 'VARIETY' }], measures: [{ trait: 'yield_per_mu', agg: 'AVG' }],
  },
  {
    key: 'stability', title: '产量稳定性', desc: '各品种折合亩产的变异系数，越小越稳',
    chartType: 'bar', dimensions: [{ type: 'VARIETY' }], measures: [{ trait: 'yield_per_mu', agg: 'CV' }], sorted: true,
  },
  {
    key: 'rust', title: '条锈病反应型分布', desc: '各级别有多少个小区',
    chartType: 'donut', dimensions: [{ type: 'TRAIT', trait: 'stripe_rust_reaction' }], measures: [{ agg: 'COUNT' }],
  },
  {
    key: 'height-yield', title: '株高和产量的关系', desc: '每个小区一个点',
    chartType: 'scatter', dimensions: [], measures: [{ trait: 'plant_height', agg: 'AVG' }, { trait: 'yield_per_mu', agg: 'AVG' }],
  },
  {
    key: 'profile', title: '品种性状雷达', desc: '株高、穗数、粒数、千粒重、亩产放在一起看',
    chartType: 'radar', dimensions: [{ type: 'VARIETY' }],
    measures: ['plant_height', 'effective_spikes', 'grains_per_spike', 'thousand_grain_weight', 'yield_per_mu']
      .map((trait) => ({ trait, agg: 'AVG' as AnalysisAgg })),
  },
  {
    key: 'heat', title: '品种 × 年份热力图', desc: '颜色越深亩产越高',
    chartType: 'heatmap', dimensions: [{ type: 'VARIETY' }, { type: 'YEAR' }], measures: [{ trait: 'yield_per_mu', agg: 'AVG' }],
  },
]

/** 第 i 个分组在这种图里起什么作用，显示在分组标签上 */
export function dimensionRole(chartType: ChartType, i: number): string {
  switch (chartType) {
    case 'heatmap':
      return i === 0 ? '纵轴' : '横轴'
    case 'pie':
    case 'donut':
      return '扇区'
    case 'radar':
      return '每条线'
    case 'scatter':
      return '颜色'
    case 'table':
      return '行'
    default:
      return i === 0 ? '横轴' : '颜色'
  }
}

/** 设置是否画得出这种图；画不出时返回原因（直接显示给老师） */
export function chartProblem(c: Pick<ChartConfig, 'chartType' | 'dimensions' | 'measures'>): string | null {
  const d = c.dimensions.length
  const m = c.measures.length
  if (m === 0) return '至少选一个指标'
  switch (c.chartType) {
    case 'pie':
    case 'donut':
      return d === 1 && m === 1 ? null : '饼图要一个分组、一个指标'
    case 'scatter':
      return m >= 2 ? null : '散点图要两个指标：一个做横轴，一个做纵轴'
    case 'radar':
      return d >= 1 && m >= 3 ? null : '雷达图要一个分组（如品种）和至少三个指标'
    case 'heatmap':
      return d === 2 && m >= 1 ? null : '热力图要两个分组（如品种和年份）和一个指标'
    case 'line':
    case 'area':
    case 'bar':
    case 'hbar':
    case 'stack':
      return d >= 1 ? null : '这种图要至少一个分组'
    default:
      return null
  }
}

/** 画图用的结果：分组列、指标列分开 */
interface Split {
  dimCols: number[]
  measureCols: number[]
}

function split(result: AnalysisResult): Split {
  const dimCols: number[] = []
  const measureCols: number[] = []
  result.columns.forEach((c, i) => (c.kind === 'DIMENSION' ? dimCols : measureCols).push(i))
  return { dimCols, measureCols }
}

const num = (v: unknown): number | null => (typeof v === 'number' ? v : v === null || v === undefined || v === '' ? null : Number(v))
const unique = <T>(list: T[]) => [...new Set(list)]

function measureName(result: AnalysisResult, col: number): string {
  const c = result.columns[col]!
  return c.unit && c.unit !== '级' && c.unit !== '个' ? `${c.label}（${c.unit}）` : c.label
}

function digitsOf(values: (number | null)[]): number {
  return Math.min(2, Math.max(0, ...values.map((v) => (v === null ? 0 : (String(v).split('.')[1]?.length ?? 0)))))
}

/** 把查询结果按设置画成 ECharts 图 */
export function buildOption(result: AnalysisResult, c: ChartConfig, t: ChartTheme): EChartsCoreOption {
  const base = {
    animationDuration: 500,
    color: t.series,
    textStyle: { fontFamily: FONT, color: t.textSecondary },
    tooltip: { ...tooltipStyle(t) },
  }
  const { dimCols, measureCols } = split(result)
  // 各指标都没有值的组不画（例如刚播种的年份还没有产量）
  const rows = result.rows.filter((r) => measureCols.some((m) => num(r[m]) !== null))
  switch (c.chartType) {
    case 'pie':
    case 'donut':
      return pie(result, rows, dimCols, measureCols, c, t, base)
    case 'scatter':
      return scatter(result, rows, dimCols, measureCols, t, base)
    case 'radar':
      return radar(result, rows, dimCols, measureCols, t, base)
    case 'heatmap':
      return heatmap(result, rows, dimCols, measureCols, c, t, base)
    default:
      return cartesian(result, rows, dimCols, measureCols, c, t, base)
  }
}

type Base = Record<string, unknown>

/** 柱状、条形、堆积、折线、面积：第一个分组做横轴；有第二个分组时按它分系列，否则每个指标一个系列 */
function cartesian(result: AnalysisResult, rows: AnalysisResult['rows'], dimCols: number[], measureCols: number[],
  c: ChartConfig, t: ChartTheme, base: Base): EChartsCoreOption {
  const d0 = dimCols[0] ?? 0
  const d1 = dimCols[1]
  let categories = unique(rows.map((r) => String(r[d0])))
  let series: { name: string; values: (number | null)[] }[]
  if (d1 !== undefined) {
    const m = measureCols[0]!
    const groups = unique(rows.map((r) => String(r[d1]))).slice(0, MAX_SERIES)
    series = groups.map((g) => ({
      name: g,
      values: categories.map((cat) => num(rows.find((r) => String(r[d0]) === cat && String(r[d1]) === g)?.[m])),
    }))
  } else {
    series = measureCols.map((m) => ({
      name: measureName(result, m),
      values: categories.map((cat) => num(rows.find((r) => String(r[d0]) === cat)?.[m])),
    }))
  }
  if (c.sorted && series.length >= 1 && (c.chartType === 'bar' || c.chartType === 'hbar')) {
    const order = categories.map((cat, i) => ({ cat, i, v: series[0]!.values[i] ?? -Infinity }))
      .sort((a, b) => (c.chartType === 'hbar' ? a.v - b.v : b.v - a.v))
    categories = order.map((o) => o.cat)
    series = series.map((s) => ({ ...s, values: order.map((o) => s.values[o.i]!) }))
  }
  const horizontal = c.chartType === 'hbar'
  const isLine = c.chartType === 'line' || c.chartType === 'area'
  const unitCols = d1 !== undefined ? [measureCols[0]!] : measureCols
  const units = unique(unitCols.map((m) => result.columns[m]!.unit ?? ''))
  // 两个指标单位不同时用两根纵轴
  const dualAxis = !horizontal && d1 === undefined && measureCols.length === 2 && units.length === 2
  const digits = digitsOf(series.flatMap((s) => s.values))
  // 线多了数字会挤在一起；点少时曲线会被拉出不存在的起伏
  const showLabels = c.showValues && (!isLine || series.length <= 2)
  const smooth = isLine && categories.length >= 6 ? 0.25 : false
  const valueAxis = (i: number) => ({
    type: 'value', ...axisStyle(t), name: dualAxis ? units[i] : units.length === 1 ? units[0] : '',
    scale: isLine, splitLine: { lineStyle: { color: t.grid } },
  })
  const categoryAxis = {
    type: 'category', data: categories, ...axisStyle(t),
    axisLabel: { color: t.textSecondary, fontFamily: FONT, interval: 0, rotate: !horizontal && categories.length > 8 ? 30 : 0 },
    boundaryGap: !isLine,
  }
  return {
    ...base,
    legend: series.length > 1 ? { top: 0, textStyle: { color: t.textSecondary, fontFamily: FONT }, icon: 'roundRect' } : undefined,
    grid: { left: 12, right: 24, top: series.length > 1 ? 40 : 20, bottom: 12, containLabel: true },
    tooltip: { ...base.tooltip as object, trigger: 'axis', valueFormatter: (v: number) => formatNumber(v, digits) },
    xAxis: horizontal ? valueAxis(0) : categoryAxis,
    yAxis: horizontal ? categoryAxis : dualAxis ? [valueAxis(0), valueAxis(1)] : valueAxis(0),
    dataZoom: !horizontal && categories.length > 24 ? [{ type: 'slider', height: 16, bottom: 0 }] : undefined,
    series: series.map((s, i) => ({
      name: s.name,
      type: isLine ? 'line' : 'bar',
      data: s.values,
      yAxisIndex: dualAxis ? i : 0,
      stack: c.chartType === 'stack' ? 'total' : undefined,
      smooth,
      symbolSize: 7,
      connectNulls: true,
      areaStyle: c.chartType === 'area' ? { opacity: 0.18 } : undefined,
      barMaxWidth: 34,
      itemStyle: isLine ? undefined : { borderRadius: horizontal ? [0, 5, 5, 0] : [5, 5, 0, 0] },
      label: showLabels
        ? { show: true, position: horizontal ? 'right' : 'top', color: t.textSecondary, fontSize: 11, formatter: (p: { value: number }) => formatNumber(p.value, digits) }
        : undefined,
    })),
  }
}

function pie(result: AnalysisResult, rows: AnalysisResult['rows'], dimCols: number[], measureCols: number[],
  c: ChartConfig, t: ChartTheme, base: Base): EChartsCoreOption {
  const d0 = dimCols[0] ?? 0
  const m = measureCols[0]!
  const data = rows.map((r) => ({ name: String(r[d0]), value: num(r[m]) ?? 0 })).filter((x) => x.value > 0)
  const unit = result.columns[m]!.unit ?? ''
  return {
    ...base,
    color: [...t.series, ...t.series.map((s) => s + 'aa')],
    legend: { type: 'scroll', orient: 'vertical', right: 8, top: 'middle', textStyle: { color: t.textSecondary, fontFamily: FONT } },
    tooltip: { ...base.tooltip as object, trigger: 'item', formatter: (p: { name: string; value: number; percent: number }) => `<b>${p.name}</b><br>${formatNumber(p.value, 2)} ${unit}（${p.percent}%）` },
    series: [{
      type: 'pie',
      radius: c.chartType === 'donut' ? ['46%', '72%'] : [0, '72%'],
      center: ['40%', '52%'],
      data,
      itemStyle: { borderColor: t.surface, borderWidth: 2, borderRadius: c.chartType === 'donut' ? 6 : 2 },
      label: { color: t.textSecondary, fontFamily: FONT, formatter: c.showValues ? '{b}\n{d}%' : '{b}' },
      labelLine: { lineStyle: { color: t.axis } },
    }],
  }
}

/** 散点图：前两个指标做横轴、纵轴；按第一个分组（没有分组时按品种）分颜色 */
function scatter(result: AnalysisResult, rows: AnalysisResult['rows'], dimCols: number[], measureCols: number[],
  t: ChartTheme, base: Base): EChartsCoreOption {
  const [mx, my] = measureCols as [number, number]
  // 不分组时每个小区一行，列是 年份、小区、品种、重复
  const groupCol = dimCols.length === 4 ? dimCols[2]! : dimCols[0]
  const labelCols = dimCols
  const groups = groupCol === undefined ? ['全部'] : unique(rows.map((r) => String(r[groupCol]))).slice(0, MAX_SERIES)
  return {
    ...base,
    legend: groups.length > 1 ? { type: 'scroll', top: 0, textStyle: { color: t.textSecondary, fontFamily: FONT } } : undefined,
    grid: { left: 12, right: 24, top: groups.length > 1 ? 40 : 20, bottom: 30, containLabel: true },
    tooltip: {
      ...base.tooltip as object, trigger: 'item',
      formatter: (p: { data: [number, number, string] }) => `<b>${p.data[2]}</b><br>${measureName(result, mx)}：${p.data[0]}<br>${measureName(result, my)}：${p.data[1]}`,
    },
    xAxis: { type: 'value', scale: true, name: measureName(result, mx), nameLocation: 'middle', nameGap: 28, ...axisStyle(t) },
    yAxis: { type: 'value', scale: true, name: measureName(result, my), ...axisStyle(t) },
    series: groups.map((g) => ({
      name: g,
      type: 'scatter',
      symbolSize: 11,
      itemStyle: { opacity: 0.82, borderColor: '#fff', borderWidth: 1 },
      data: rows
        .filter((r) => groupCol === undefined || String(r[groupCol]) === g)
        .filter((r) => num(r[mx]) !== null && num(r[my]) !== null)
        .map((r) => [num(r[mx]), num(r[my]), labelCols.map((i) => r[i]).join(' · ')]),
    })),
  }
}

/** 雷达图：每个指标一根轴（按各自最大值归一），第一个分组的每个取值一条线 */
function radar(result: AnalysisResult, rows: AnalysisResult['rows'], dimCols: number[], measureCols: number[],
  t: ChartTheme, base: Base): EChartsCoreOption {
  const d0 = dimCols[0] ?? 0
  const groups = rows.slice(0, MAX_SERIES)
  const indicator = measureCols.map((m) => {
    const values = rows.map((r) => num(r[m]) ?? 0)
    const max = Math.max(...values)
    const min = Math.min(...values)
    // 从最小值的八成开始，差别才看得出来
    return { name: result.columns[m]!.label.replace(/ · .*/, ''), max: max * 1.04, min: Math.max(0, min * 0.8) }
  })
  return {
    ...base,
    legend: { type: 'scroll', bottom: 0, textStyle: { color: t.textSecondary, fontFamily: FONT } },
    tooltip: { ...base.tooltip as object, trigger: 'item' },
    radar: {
      indicator,
      radius: '64%',
      center: ['50%', '48%'],
      axisName: { color: t.textSecondary, fontFamily: FONT },
      splitLine: { lineStyle: { color: t.grid } },
      splitArea: { areaStyle: { color: [t.surface, t.grid + '55'] } },
      axisLine: { lineStyle: { color: t.axis } },
    },
    series: [{
      type: 'radar',
      symbolSize: 5,
      areaStyle: { opacity: 0.08 },
      data: groups.map((r) => ({ name: String(r[d0]), value: measureCols.map((m) => num(r[m])) })),
    }],
  }
}

/** 热力图：第一个分组做纵轴、第二个做横轴，第一个指标的大小用颜色深浅表示 */
function heatmap(result: AnalysisResult, rows: AnalysisResult['rows'], dimCols: number[], measureCols: number[],
  c: ChartConfig, t: ChartTheme, base: Base): EChartsCoreOption {
  const [d0, d1] = dimCols as [number, number]
  const m = measureCols[0]!
  const ys = unique(rows.map((r) => String(r[d0])))
  const xs = unique(rows.map((r) => String(r[d1])))
  const values = rows.map((r) => num(r[m])).filter((v): v is number => v !== null)
  const digits = digitsOf(values)
  return {
    ...base,
    grid: { left: 12, right: 24, top: 12, bottom: 56, containLabel: true },
    tooltip: {
      ...base.tooltip as object, trigger: 'item',
      formatter: (p: { data: [number, number, number] }) => `<b>${ys[p.data[1]]}</b> · ${xs[p.data[0]]}<br>${measureName(result, m)}：${formatNumber(p.data[2], digits)}`,
    },
    xAxis: { type: 'category', data: xs, ...axisStyle(t), splitArea: { show: true } },
    yAxis: { type: 'category', data: ys, ...axisStyle(t), axisLabel: { color: t.textSecondary, fontFamily: FONT } },
    visualMap: {
      min: values.length ? Math.min(...values) : 0,
      max: values.length ? Math.max(...values) : 1,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemHeight: 140,
      textStyle: { color: t.textSecondary },
      inRange: { color: t.surface === '#fffdf8' ? ['#f4eedc', '#c3d9a6', '#77ad7c', '#2e6b4f'] : ['#16261d', '#3b6b4f', '#b89a45', '#e2b65a'] },
    },
    series: [{
      type: 'heatmap',
      data: rows.flatMap((r) => {
        const v = num(r[m])
        return v === null ? [] : [[xs.indexOf(String(r[d1])), ys.indexOf(String(r[d0])), v]]
      }),
      label: {
        show: c.showValues || xs.length * ys.length <= 60, color: t.textPrimary, fontSize: 11,
        textBorderColor: t.surface, textBorderWidth: 2, formatter: (p: { data: number[] }) => formatNumber(p.data[2]!, digits),
      },
      itemStyle: { borderColor: t.surface, borderWidth: 2, borderRadius: 4 },
    }],
  }
}
