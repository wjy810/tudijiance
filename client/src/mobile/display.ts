// 手机端显示用的小工具
import type { TraitValue, TraitView } from '@/shared/api'

/** 录入的值显示成文字，例如"78 cm""3 级""复合肥 40 kg/亩"。和服务器的显示规则一致 */
export function displayValue(t: TraitView, v: TraitValue): string {
  const unit = t.trait.unit && t.trait.unit !== '级' ? ` ${t.trait.unit}` : ''
  const label = (code: string | null) => t.options.find((o) => o.valueCode === code)?.label ?? code ?? ''
  switch (t.trait.dataType) {
    case 'DATE':
      return v.valueDate ?? ''
    case 'GRADE':
      return v.valueNum === null ? '' : `${v.valueNum} 级`
    case 'NUMBER':
      return v.valueNum === null ? '' : `${v.valueNum}${unit}`
    case 'OPTION':
      return label(v.valueText)
    case 'OPERATION':
      return `${label(v.valueText)}${v.valueNum !== null && v.valueNum !== undefined ? ` ${v.valueNum}${unit}` : ''}`
    default:
      return v.valueText ?? ''
  }
}

/** "刚刚""3 分钟前""今天 08:30""10-28 16:05" */
export function timeAgo(ms: number | null): string {
  if (!ms) {
    return '还没有'
  }
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  const sameDay = new Date().toDateString() === d.toDateString()
  return sameDay ? `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}` : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 小区的叫法：有编号用编号，没有用"行-列" */
export function plotCode(p: { code: string | null; rowNo: number; colNo: number }): string {
  return p.code || `${p.rowNo}-${p.colNo}`
}
