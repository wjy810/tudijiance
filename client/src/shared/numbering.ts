// 小区批量编号规则。桌面端和以后的手机端共用

export type NumberingRule = 'ROW' | 'SNAKE' | 'ROW_COL' | 'PREFIX_SEQ'
export type Direction = 'ROWS' | 'COLUMNS'

export interface NumberingOptions {
  rule: NumberingRule
  /** 按行（先排满一行再到下一行）还是按列 */
  direction: Direction
  /** 起始号，用于 ROW、SNAKE、PREFIX_SEQ */
  start: number
  /** 前缀，例如 A → A001 */
  prefix: string
  /** 序号补零到几位，0 表示不补零 */
  pad: number
}

export interface NumberingCell {
  id: number
  rowNo: number
  colNo: number
}

export const NUMBERING_RULES: { value: NumberingRule; label: string; example: string }[] = [
  { value: 'ROW', label: '按顺序编号', example: '1、2、3……' },
  { value: 'SNAKE', label: '蛇形编号', example: '第一行从左到右，第二行从右到左' },
  { value: 'ROW_COL', label: '行号-列号', example: '3-05' },
  { value: 'PREFIX_SEQ', label: '前缀 + 流水号', example: 'A001、A002' },
]

export function defaultOptions(rule: NumberingRule): NumberingOptions {
  if (rule === 'PREFIX_SEQ') {
    return { rule, direction: 'ROWS', start: 1, prefix: 'A', pad: 3 }
  }
  return { rule, direction: 'ROWS', start: 1, prefix: '', pad: 0 }
}

/**
 * 给选中的小区生成编号，返回 小区 id → 编号。
 * 只对传入的小区编号；序号按选中范围内的顺序从 start 开始连续编。
 */
export function generateCodes(cells: NumberingCell[], options: NumberingOptions): Map<number, string> {
  const result = new Map<number, string>()
  if (cells.length === 0) {
    return result
  }
  if (options.rule === 'ROW_COL') {
    for (const c of cells) {
      result.set(c.id, `${options.prefix}${c.rowNo}-${String(c.colNo).padStart(2, '0')}`)
    }
    return result
  }

  // 主方向：按行时先比较行号，按列时先比较列号
  const major = (c: NumberingCell) => (options.direction === 'ROWS' ? c.rowNo : c.colNo)
  const minor = (c: NumberingCell) => (options.direction === 'ROWS' ? c.colNo : c.rowNo)
  const majors = [...new Set(cells.map(major))].sort((a, b) => a - b)
  const majorIndex = new Map(majors.map((m, i) => [m, i]))

  const ordered = [...cells].sort((a, b) => {
    if (major(a) !== major(b)) {
      return major(a) - major(b)
    }
    // 蛇形：第 2、4……行（或列）倒着编
    const reverse = options.rule === 'SNAKE' && (majorIndex.get(major(a)) ?? 0) % 2 === 1
    return reverse ? minor(b) - minor(a) : minor(a) - minor(b)
  })

  ordered.forEach((c, i) => {
    const seq = String(options.start + i)
    const padded = options.pad > 0 ? seq.padStart(options.pad, '0') : seq
    result.set(c.id, `${options.prefix}${padded}`)
  })
  return result
}
