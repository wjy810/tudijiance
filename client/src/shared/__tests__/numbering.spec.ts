import { describe, expect, it } from 'vitest'

import { defaultOptions, generateCodes, type NumberingCell } from '../numbering'

// 2 行 × 3 列的网格，id = 行号 * 10 + 列号
function grid(rows: number, cols: number): NumberingCell[] {
  const cells: NumberingCell[] = []
  for (let r = 1; r <= rows; r++) {
    for (let c = 1; c <= cols; c++) {
      cells.push({ id: r * 10 + c, rowNo: r, colNo: c })
    }
  }
  return cells
}

const codesInIdOrder = (m: Map<number, string>) => [...m.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1])

describe('generateCodes', () => {
  it('按行顺序编号', () => {
    const codes = generateCodes(grid(2, 3), defaultOptions('ROW'))
    expect(codesInIdOrder(codes)).toEqual(['1', '2', '3', '4', '5', '6'])
  })

  it('蛇形编号：第二行倒过来', () => {
    const codes = generateCodes(grid(2, 3), defaultOptions('SNAKE'))
    // 第一行 11,12,13 → 1,2,3；第二行 21,22,23 → 6,5,4
    expect(codesInIdOrder(codes)).toEqual(['1', '2', '3', '6', '5', '4'])
  })

  it('按列的蛇形编号', () => {
    const codes = generateCodes(grid(2, 3), { ...defaultOptions('SNAKE'), direction: 'COLUMNS' })
    // 第 1 列 11,21 → 1,2；第 2 列 12,22 → 4,3；第 3 列 13,23 → 5,6
    expect(codes.get(11)).toBe('1')
    expect(codes.get(21)).toBe('2')
    expect(codes.get(22)).toBe('3')
    expect(codes.get(12)).toBe('4')
    expect(codes.get(13)).toBe('5')
    expect(codes.get(23)).toBe('6')
  })

  it('行号-列号，列号补零到两位', () => {
    const codes = generateCodes(grid(2, 3), defaultOptions('ROW_COL'))
    expect(codes.get(23)).toBe('2-03')
  })

  it('前缀 + 流水号，从指定号码开始', () => {
    const codes = generateCodes(grid(1, 3), { ...defaultOptions('PREFIX_SEQ'), start: 9 })
    expect(codesInIdOrder(codes)).toEqual(['A009', 'A010', 'A011'])
  })

  it('只给选中的小区编号，选中范围内连续编', () => {
    const selected = grid(3, 3).filter((c) => c.rowNo >= 2 && c.colNo === 2)
    const codes = generateCodes(selected, { ...defaultOptions('ROW'), start: 101 })
    expect(codes.size).toBe(2)
    expect(codes.get(22)).toBe('101')
    expect(codes.get(32)).toBe('102')
  })

  it('没有选中时返回空结果', () => {
    expect(generateCodes([], defaultOptions('ROW')).size).toBe(0)
  })
})
