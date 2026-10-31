// 图表的配色和样式。分类色按固定顺序分配（已经过色盲区分度检查），浅色用于普通页面，深色用于大屏
import { use } from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart, RadarChart, ScatterChart } from 'echarts/charts'
import {
  DataZoomComponent, GridComponent, LegendComponent, MarkLineComponent, RadarComponent, TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, LineChart, PieChart, ScatterChart, RadarChart, HeatmapChart, GridComponent, TooltipComponent,
  LegendComponent, DataZoomComponent, MarkLineComponent, RadarComponent, VisualMapComponent, CanvasRenderer])

export interface ChartTheme {
  surface: string
  textPrimary: string
  textSecondary: string
  textMuted: string
  grid: string
  axis: string
  /** 对照品种的柱子用中性灰，表示"基准" */
  reference: string
  series: string[]
  tooltipBg: string
  tooltipBorder: string
}

export const LIGHT: ChartTheme = {
  surface: '#fffdf8',
  textPrimary: '#1f2a22',
  textSecondary: '#5c5b4d',
  textMuted: '#978f7a',
  grid: '#ece4d3',
  axis: '#d5c8ab',
  reference: '#b3aa94',
  // 田野绿、麦穗金、石板蓝、陶土、橄榄、梅紫、青绿、玫瑰：彼此色相、明度都拉开
  series: ['#2e6b4f', '#d09a2c', '#4b7a93', '#b0543a', '#8a9a2e', '#7d5a9e', '#3a9c8c', '#c46a86'],
  tooltipBg: '#fffdf8',
  tooltipBorder: 'rgba(120,100,60,0.18)',
}

export const DARK: ChartTheme = {
  surface: '#0f1d16',
  textPrimary: '#f3ead2',
  textSecondary: '#c9bf9f',
  textMuted: '#8d8670',
  grid: 'rgba(226,182,90,0.10)',
  axis: 'rgba(226,182,90,0.22)',
  reference: '#7b7663',
  // 大屏：麦穗金为主，配亮一些的绿、蓝、陶土
  series: ['#e2b65a', '#5fbf8a', '#6fa8c8', '#e0815f', '#b8c45a', '#a98bd0', '#4fc1b0', '#e08aa6'],
  tooltipBg: '#132219',
  tooltipBorder: 'rgba(226,182,90,0.35)',
}

/** 同时显示的系列最多 8 个，再多颜色就分不清了 */
export const MAX_SERIES = 8

export const FONT = "'Microsoft YaHei UI', 'Microsoft YaHei', 'PingFang SC', 'Noto Sans CJK SC', system-ui, sans-serif"
/** 图表标题、大数字用的宋体 */
export const SERIF = "'Noto Serif SC', 'Songti SC', serif"

export function axisStyle(t: ChartTheme) {
  return {
    axisLine: { lineStyle: { color: t.axis } },
    axisTick: { show: false },
    axisLabel: { color: t.textMuted, fontFamily: FONT },
    splitLine: { lineStyle: { color: t.grid, width: 1, type: 'solid' as const } },
    nameTextStyle: { color: t.textMuted, fontFamily: FONT },
  }
}

export function tooltipStyle(t: ChartTheme) {
  return {
    backgroundColor: t.tooltipBg,
    borderColor: t.tooltipBorder,
    borderWidth: 1,
    textStyle: { color: t.textPrimary, fontFamily: FONT, fontSize: 12 },
    extraCssText: 'box-shadow: 0 8px 24px -8px rgba(60,45,20,0.3); border-radius: 10px; padding: 8px 12px;',
  }
}

/** 数字按单位的小数位显示，去掉多余的 0 */
export function formatNumber(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) {
    return '—'
  }
  return Number(v.toFixed(digits)).toLocaleString('zh-CN')
}

export function formatPercent(v: number | null | undefined): string {
  if (v === null || v === undefined) {
    return ''
  }
  return `${v > 0 ? '+' : ''}${v.toFixed(1)}%`
}

/**
 * 给实体分配固定的颜色槽：已经有颜色的保持不变，新选中的用最小的空槽。
 * 这样增减显示的品种时，其它品种的颜色不会跟着变。
 */
export function assignSlots(previous: Map<number, number>, ids: number[]): Map<number, number> {
  const next = new Map<number, number>()
  for (const id of ids) {
    const slot = previous.get(id)
    if (slot !== undefined) {
      next.set(id, slot)
    }
  }
  const used = new Set(next.values())
  for (const id of ids) {
    if (next.has(id)) {
      continue
    }
    let slot = 0
    while (used.has(slot)) {
      slot++
    }
    next.set(id, slot)
    used.add(slot)
  }
  return next
}
