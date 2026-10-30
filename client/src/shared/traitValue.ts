// 录入值的辅助函数。桌面端和以后的手机端共用
import type { TraitDef, TraitValue, TraitView } from '@/shared/api'

export function emptyValue(): TraitValue {
  return { valueNum: null, valueText: null, valueDate: null }
}

/** 今天的日期，格式 2026-10-30（按本机时区） */
export function today(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 检查录入的值是否填完整，返回提示文字；填好了返回 null。和后端的检查一致 */
export function missingValue(t: TraitView, v: TraitValue): string | null {
  const name = t.trait.name
  switch (t.trait.dataType) {
    case 'DATE':
      return v.valueDate ? null : `${name}：请选择日期`
    case 'NUMBER':
      return v.valueNum === null || v.valueNum === undefined ? `${name}：请填写数值` : null
    case 'GRADE':
      return v.valueNum === null || v.valueNum === undefined ? `${name}：请选择级别` : null
    case 'OPTION':
    case 'OPERATION':
      return v.valueText ? null : `${name}：请选择${t.trait.dataType === 'OPERATION' ? '类型' : '一项'}`
    case 'TEXT':
      return v.valueText?.trim() ? null : `${name}：请填写内容`
  }
}

/** 只保留这个类型用到的字段，其余清空，避免把切换项目前填的值带过去 */
export function normalizeValue(trait: TraitDef, v: TraitValue): TraitValue {
  switch (trait.dataType) {
    case 'DATE':
      return { valueNum: null, valueText: null, valueDate: v.valueDate }
    case 'NUMBER':
    case 'GRADE':
      return { valueNum: v.valueNum, valueText: null, valueDate: null }
    case 'OPERATION':
      return { valueNum: v.valueNum, valueText: v.valueText, valueDate: null }
    default:
      return { valueNum: null, valueText: v.valueText?.trim() || null, valueDate: null }
  }
}

/** 日期类项目（例如出苗期）本身就是日期，调查日期直接用它 */
export function observedOnFor(trait: TraitDef, v: TraitValue, observedOn: string): string {
  return trait.dataType === 'DATE' && v.valueDate ? v.valueDate : observedOn
}

export function newUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 老环境没有 randomUUID 时的退路
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}
