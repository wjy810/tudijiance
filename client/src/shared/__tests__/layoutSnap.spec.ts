import { describe, expect, it } from 'vitest'

import { UNIT_PRESETS, createLayout } from '@/shared/fieldLayout'
import { nearestLine, resizeBy, roadStrip, snapLines, snapMove, snapPoint } from '@/shared/layoutSnap'

/** 2 行 2 列区块（6 × 10），中间横竖各留 1 的空当，没有道路 */
function land() {
  const l = createLayout({ blockRows: 2, blockCols: 2, blockW: 6, blockH: 10, unit: UNIT_PRESETS[0]!, innerRows: 2, innerCols: 3, roadW: 1 })
  l.blocks = l.blocks.filter((b) => b.kind !== 'ROAD')
  return l
}

describe('snap lines', () => {
  it('collects block edges and land edges, leaving out the blocks being moved', () => {
    const lines = snapLines(land(), ['B1'])
    expect(lines.xs.map((s) => s.pos).sort((a, b) => a - b)).toEqual([0, 0, 6, 7, 7, 13, 13, 13])
    expect(lines.ys.some((s) => s.pos === 10)).toBe(true)
  })

  it('adds lines one road width away, and plot lines for drawing roads', () => {
    const lines = snapLines(land(), [], 1, true)
    // B1 右边 6，再往右 1 是 7（也是 B2 的左边）
    expect(lines.xs.filter((s) => s.pos === 7).map((s) => s.kind).sort()).toEqual(['edge', 'edge', 'gap', 'gap'])
    // B1 里 3 列小区块之间的线
    expect(lines.xs.filter((s) => s.kind === 'seam').map((s) => s.pos).slice(0, 2)).toEqual([2, 4])
    expect(lines.ys.filter((s) => s.kind === 'seam').map((s) => s.pos)).toContain(5)
  })

  it('prefers the nearer line, and an edge over a gap line at the same distance', () => {
    const lines = snapLines(land(), [], 1).xs
    expect(nearestLine(6.2, lines, 0.5)?.pos).toBe(6)
    expect(nearestLine(7, lines, 0.5)?.kind).toBe('edge')
    expect(nearestLine(3, lines, 0.5)).toBeNull()
  })
})

describe('snapping moves', () => {
  it('pulls the nearer edge of the moved box onto a line', () => {
    const lines = snapLines(land(), ['B1'], 1)
    // B1 拖到右下，左边离 B2 的右边（13）加一个路宽（14）差 0.2
    const res = snapMove({ x: 14.2, y: 0.3, w: 6, h: 10 }, lines, 0.4)
    expect(res.dx).toBeCloseTo(-0.2)
    expect(res.dy).toBeCloseTo(-0.3)
    expect(res.guides.map((g) => g.axis)).toEqual(['x', 'y'])
  })

  it('rounds to the step when nothing is near', () => {
    const res = snapMove({ x: 30.26, y: 40.71, w: 6, h: 10 }, snapLines(land(), ['B1']), 0.2, 0.5)
    expect(res.dx).toBeCloseTo(0.24)
    expect(res.dy).toBeCloseTo(-0.21)
    expect(res.guides).toEqual([])
  })
})

describe('resizing and drawing', () => {
  it('moves only the dragged edges and keeps a minimum size', () => {
    const lines = snapLines(land(), ['B1'])
    const start = { x: 0, y: 0, w: 6, h: 10 }
    expect(resizeBy(start, 'e', { x: 6.9, y: 50 }, lines, 0.3, 0.5).rect).toEqual({ x: 0, y: 0, w: 7, h: 10 })
    expect(resizeBy(start, 'se', { x: 4.26, y: 8.26 }, lines, 0.1, 0.5, 0.5).rect).toEqual({ x: 0, y: 0, w: 4.5, h: 8.5 })
    expect(resizeBy(start, 'w', { x: 9, y: 0 }, lines, 0.1, 0.5).rect).toEqual({ x: 5.5, y: 0, w: 0.5, h: 10 })
    expect(resizeBy(start, 'n', { x: 0, y: 3 }, lines, 0.1, 0.5).rect).toEqual({ x: 0, y: 3, w: 6, h: 7 })
  })

  it('snaps a point on both axes', () => {
    const p = snapPoint({ x: 6.1, y: 9.9 }, snapLines(land()), 0.2)
    expect([p.x, p.y]).toEqual([6, 10])
    expect(p.guides).toHaveLength(2)
  })

  it('turns a drag into a road strip along the longer direction', () => {
    expect(roadStrip({ x: 0, y: 10.5 }, { x: 13, y: 11 }, 1)).toEqual({ axis: 'row', rect: { x: 0, y: 10, w: 13, h: 1 } })
    expect(roadStrip({ x: 6.5, y: 21 }, { x: 7, y: 0 }, 1)).toEqual({ axis: 'col', rect: { x: 6, y: 0, w: 1, h: 21 } })
  })
})
