import { describe, expect, it } from 'vitest'

import {
  UNIT_PRESETS, alignBlockFit, anchors, blockRect, canMoveCells, createLayout, expandRect, fieldSize, innerCells, innerSizes,
  insertTrack, mergeBlocks, mergeInner, moveBlockFit, moveCells, plotCount, removeTrack, removedPlots, resizeBlockGrid, resizeInner,
  plotPlace, plotPlaces, setBlockFit, setBlockKind, setInnerTrackPair, setInnerTrackSize, setTrackPair, setTrackSize, setUnit,
  splitBlock, splitInner, swapBlocks, type FieldLayout,
} from '@/shared/fieldLayout'

const METER = UNIT_PRESETS[0]!

/** 2 行 3 列区块，每块 6 m × 13.3 m，里面 2 行 3 列小区；给小区编上 id，模拟保存过 */
function saved(roadW = 0): FieldLayout {
  const l = createLayout({ blockRows: 2, blockCols: 3, blockW: 6, blockH: 13.3, unit: METER, innerRows: 2, innerCols: 3, roadW })
  let id = 1
  for (const b of l.blocks) {
    if (!b.inner) continue
    for (const a of anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges)) b.inner.plots[`${a.r},${a.c}`] = id++
  }
  return l
}

const block = (l: FieldLayout, id: string) => l.blocks.find((b) => b.id === id)!

describe('createLayout', () => {
  it('builds a block grid with the given sizes', () => {
    const l = createLayout({ blockRows: 2, blockCols: 3, blockW: 6, blockH: 13.3, unit: METER, innerRows: 2, innerCols: 3, roadW: 0 })
    expect(l.blocks).toHaveLength(6)
    expect(fieldSize(l)).toEqual({ w: 18, h: 26.6 })
    expect(plotCount(l)).toBe(36)
    expect(block(l, 'B5').r).toBe(1)
    expect(block(l, 'B5').c).toBe(1)
  })

  it('leaves roads between blocks', () => {
    const l = saved(1.5)
    expect(l.rows).toEqual([13.3, 1.5, 13.3])
    expect(l.cols).toEqual([6, 1.5, 6, 1.5, 6])
    expect(l.blocks.filter((b) => b.kind === 'PLANT')).toHaveLength(6)
    // 横向道路合并成整行
    expect(l.merges).toEqual([{ r: 1, c: 0, rs: 1, cs: 5 }])
    expect(plotCount(l)).toBe(36)
  })
})

describe('geometry', () => {
  it('places plots inside blocks', () => {
    const l = saved()
    const cells = innerCells(l, block(l, 'B2'))
    expect(cells).toHaveLength(6)
    expect(cells[0]!.rect).toEqual({ x: 6, y: 0, w: 2, h: 6.65 })
    expect(cells[0]!.plotId).toBe(7)
  })
})

describe('merging blocks', () => {
  it('joins inner grids when they line up, keeping every plot', () => {
    const l = saved()
    const res = mergeBlocks(l, { r: 0, c: 0 }, { r: 0, c: 1 })
    expect(res.joined).toBe(true)
    expect(res.dropped).toEqual([])
    const b1 = block(res.layout, 'B1')
    expect(b1.inner!.cols).toHaveLength(6)
    expect(res.layout.blocks).toHaveLength(5)
    expect(removedPlots(l, res.layout)).toEqual([])
    // B2 的第一个小区（id 7）现在在合并后区块的第 0 行第 3 列，位置不变
    const moved = innerCells(res.layout, b1).find((c) => c.plotId === 7)!
    expect(moved.key).toBe('0,3')
    expect(moved.rect.x).toBeCloseTo(6)
  })

  it('splits back to the original blocks', () => {
    const merged = mergeBlocks(saved(), { r: 0, c: 0 }, { r: 1, c: 1 }).layout
    expect(merged.merges).toEqual([{ r: 0, c: 0, rs: 2, cs: 2 }])
    const split = splitBlock(merged, 'B1')
    expect(split.merges).toEqual([])
    expect(split.blocks.filter((b) => b.kind === 'PLANT')).toHaveLength(6)
    expect(removedPlots(saved(), split)).toEqual([])
    const back = split.blocks.find((b) => b.r === 1 && b.c === 1)!
    expect(Object.values(back.inner!.plots).sort((a, b) => a - b)).toEqual([25, 26, 27, 28, 29, 30])
  })

  it('drops plots of the other blocks when grids do not line up', () => {
    const l = resizeInner(saved(), 'B2', 3, 3)
    const res = mergeBlocks(l, { r: 0, c: 0 }, { r: 0, c: 1 })
    expect(res.joined).toBe(false)
    expect(res.dropped).toEqual([7, 8, 9, 10, 11, 12])
  })

  it('expands a selection to whole merged areas', () => {
    expect(expandRect([{ r: 0, c: 1, rs: 2, cs: 2 }], { r: 1, c: 0 }, { r: 1, c: 1 })).toEqual({ r: 0, c: 0, rs: 2, cs: 3 })
  })
})

describe('block rows and columns', () => {
  it('inserts a row inside a merged area and stretches it', () => {
    const merged = mergeBlocks(saved(), { r: 0, c: 0 }, { r: 1, c: 0 }).layout
    const l = insertTrack(merged, 'row', 1, 2)
    expect(l.rows).toEqual([13.3, 2, 13.3])
    expect(l.merges).toEqual([{ r: 0, c: 0, rs: 3, cs: 1 }])
    // 新的一行里只有第 1、2 列是新区块
    expect(l.blocks.filter((b) => b.r === 1).map((b) => b.c).sort()).toEqual([1, 2])
  })

  it('removes a row with its blocks and plots', () => {
    const l = removeTrack(saved(), 'row', 0)
    expect(l.rows).toEqual([13.3])
    expect(l.blocks).toHaveLength(3)
    expect(l.blocks.every((b) => b.r === 0)).toBe(true)
    expect(removedPlots(saved(), l)).toHaveLength(18)
  })

  it('resizes the block grid', () => {
    const l = resizeBlockGrid(saved(), 3, 2)
    expect(l.rows).toHaveLength(3)
    expect(l.cols).toHaveLength(2)
    expect(l.blocks).toHaveLength(6)
  })
})

describe('special blocks', () => {
  it('shrinks inside the slot, leaving road around, and moves within it', () => {
    let l = setBlockFit(saved(), 'B1', 4, 13.3)
    expect(block(l, 'B1').fit).toEqual({ x: 1, y: 0, w: 4, h: 13.3 })
    expect(blockRect(l, block(l, 'B1'))).toEqual({ x: 1, y: 0, w: 4, h: 13.3 })
    // 小区跟着变窄
    expect(innerSizes(l, block(l, 'B1')).cols.map((v) => Math.round(v * 1000) / 1000)).toEqual([1.333, 1.333, 1.333])
    l = moveBlockFit(l, 'B1', 99, 0)
    expect(block(l, 'B1').fit!.x).toBe(2)
    l = alignBlockFit(l, 'B1', 'left')
    expect(block(l, 'B1').fit!.x).toBe(0)
    // 恢复填满
    expect(setBlockFit(l, 'B1', 6, 13.3).blocks[0]!.fit).toBeNull()
  })

  it('clamps the fit when the slot gets smaller', () => {
    const l = setTrackSize(setBlockFit(saved(), 'B1', 5, 10), 'col', 0, 4)
    expect(block(l, 'B1').fit).toEqual({ x: 0, y: 1.65, w: 4, h: 10 })
    // 槽位缩到和区块一样大：恢复成普通区块
    expect(block(setTrackSize(l, 'row', 0, 10), 'B1').fit).toBeNull()
  })
})

describe('plots inside a block', () => {
  it('merges plots, keeping the top-left one', () => {
    const res = mergeInner(saved(), 'B1', { r: 0, c: 0 }, { r: 0, c: 2 })
    expect(res.dropped).toEqual([2, 3])
    const inner = block(res.layout, 'B1').inner!
    expect(inner.merges).toEqual([{ r: 0, c: 0, rs: 1, cs: 3 }])
    expect(inner.plots['0,0']).toBe(1)
    expect(innerCells(res.layout, block(res.layout, 'B1'))).toHaveLength(4)
    const split = splitInner(res.layout, 'B1', 0, 1)
    expect(innerCells(split, block(split, 'B1'))).toHaveLength(6)
    expect(block(split, 'B1').inner!.plots['0,1']).toBeUndefined()
  })

  it('sets one row size and shares the rest', () => {
    const l = setInnerTrackSize(saved(), 'B1', 'col', 0, 3)
    expect(innerSizes(l, block(l, 'B1')).cols).toEqual([3, 1.5, 1.5])
    // 不能超过区块
    expect(setInnerTrackSize(saved(), 'B1', 'col', 0, 6)).toEqual(saved())
  })

  it('resizes the inner grid and forgets plots outside', () => {
    const l = resizeInner(saved(), 'B1', 1, 2)
    expect(Object.keys(block(l, 'B1').inner!.plots).sort()).toEqual(['0,0', '0,1'])
    expect(removedPlots(saved(), l)).toEqual([3, 4, 5, 6])
  })
})

describe('kinds and units', () => {
  it('turns a block into a road and back', () => {
    const road = setBlockKind(saved(), ['B3'], 'ROAD')
    expect(block(road, 'B3').inner).toBeNull()
    expect(block(road, 'B3').name).toBe('道路')
    const back = setBlockKind(road, ['B3'], 'PLANT')
    expect(innerCells(back, block(back, 'B3'))).toHaveLength(6)
  })

  it('converts sizes when changing the unit', () => {
    const chi = UNIT_PRESETS.find((u) => u.name === '尺')!
    const l = setUnit(saved(), chi, true)
    expect(l.cols).toEqual([18, 18, 18])
    expect(setUnit(saved(), chi, false).cols).toEqual([6, 6, 6])
  })
})

describe('plotPlace', () => {
  it('names the block and the row and column inside it', () => {
    expect(plotPlace(saved(), 8)).toBe('区块2 · 第 1 行第 2 列')
    const merged = mergeInner(saved(), 'B1', { r: 0, c: 0 }, { r: 0, c: 2 }).layout
    expect(plotPlace(merged, 1)).toBe('区块1 · 第 1 行第 1–3 列')
    expect(plotPlace(saved(), 999)).toBeNull()
    expect(plotPlaces(saved()).get(36)).toBe('区块6 · 第 2 行第 3 列')
  })
})

describe('dragging', () => {
  it('moves the line between two block columns, or the outer edge', () => {
    const l = setTrackPair(saved(), 'col', 0, 4, 8)
    expect(l.cols).toEqual([4, 8, 6])
    expect(fieldSize(l).w).toBe(18)
    const wider = setTrackPair(saved(), 'col', 2, 9, null)
    expect(fieldSize(wider).w).toBe(21)
    // 不能拖成 0
    expect(setTrackPair(saved(), 'col', 0, 12, 0)).toEqual(saved())
  })

  it('moves the line between two plot rows inside a block, keeping the others', () => {
    const l = setInnerTrackPair(saved(), 'B1', 'col', 1, 3, 1)
    expect(innerSizes(l, block(l, 'B1')).cols).toEqual([2, 3, 1])
  })

  it('moves selected plots and swaps the ones in the way', () => {
    // B1：第 1 行 1 2 3，第 2 行 4 5 6。把 1、2 往下挪一行
    const l = moveCells(saved(), 'B1', ['0,0', '0,1'], 1, 0)
    const plots = block(l, 'B1').inner!.plots
    expect(plots).toEqual({ '0,0': 4, '0,1': 5, '0,2': 3, '1,0': 1, '1,1': 2, '1,2': 6 })
    expect(removedPlots(saved(), l)).toEqual([])
    // 挪出区块、挪到合并的格子上都不行
    expect(canMoveCells(saved(), 'B1', ['1,0'], 1, 0)).toBe(false)
    const merged = mergeInner(saved(), 'B1', { r: 1, c: 1 }, { r: 1, c: 2 }).layout
    expect(canMoveCells(merged, 'B1', ['0,1'], 1, 0)).toBe(false)
    expect(moveCells(merged, 'B1', ['0,1'], 1, 0)).toBe(merged)
  })

  it('carries cell kinds along when moving', () => {
    const l = saved()
    block(l, 'B1').inner!.kinds = { '0,2': 'ROAD' }
    const moved = moveCells(l, 'B1', ['0,2'], 1, 0)
    expect(block(moved, 'B1').inner!.kinds).toEqual({ '1,2': 'ROAD' })
    expect(innerCells(moved, block(moved, 'B1')).find((c) => c.key === '1,2')!.kind).toBe('ROAD')
  })

  it('swaps two blocks of the same size with their plots', () => {
    const l = swapBlocks(saved(), 'B1', 'B2')
    expect(block(l, 'B1').c).toBe(1)
    expect(block(l, 'B2').c).toBe(0)
    expect(block(l, 'B1').inner!.plots['0,0']).toBe(1)
    expect(removedPlots(saved(), l)).toEqual([])
    // 大小不一样的不能换
    const merged = mergeBlocks(saved(), { r: 0, c: 1 }, { r: 0, c: 2 }).layout
    expect(swapBlocks(merged, 'B1', 'B2')).toBe(merged)
  })
})
