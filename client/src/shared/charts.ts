// 图表的配色和样式。分类色按固定顺序分配（已经过色盲区分度检查），浅色用于普通页面，深色用于大屏
import { use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, MarkLineComponent, CanvasRenderer])

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
  surface: '#ffffff',
  textPrimary: '#0b0b0b',
  textSecondary: '#52514e',
  textMuted: '#898781',
  grid: '#e1e0d9',
  axis: '#c3c2b7',
  reference: '#a3a29b',
  series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'],
  tooltipBg: '#ffffff',
  tooltipBorder: 'rgba(11,11,11,0.10)',
}

export const DARK: ChartTheme = {
  surface: '#1a1a19',
  textPrimary: '#f0efec',
  textSecondary: '#c3c2b7',
  textMuted: '#898781',
  grid: '#2c2c2a',
  axis: '#383835',
  reference: '#6f6e69',
  series: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
  tooltipBg: '#262624',
  tooltipBorder: 'rgba(255,255,255,0.10)',
}

/** 同时显示的系列最多 8 个，再多颜色就分不清了 */
export const MAX_SERIES = 8

export const FONT = "system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif"

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
    extraCssText: 'box-shadow: 0 4px 14px rgba(0,0,0,0.12); border-radius: 6px;',
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
