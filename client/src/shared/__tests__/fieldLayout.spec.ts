import { describe, expect, it } from 'vitest'

import {
  UNIT_PRESETS, addBlock, alignBlocks, anchors, arrayBlocks, blockRect, blockSeams, canMoveCells, checkLayout, conflicts, createLayout,
  defaultRoadWidth, distributeBlocks, drawRoad, duplicateBlocks, expandRect, fieldSize, fitField, innerCells, innerSizes, mergeBlocks,
  mergeInner, moveBlocks, moveCells, plantBlocks, plotCount, plotPlace, plotPlaces, plotSize, plotSpots, regridInner, removeBlocks,
  removeRoad, removedPlots, resizeInner, setBlockKind, setBlockRect, setCellKinds, setFieldSize, setInnerTrackPair, setInnerTrackSize,
  setPlotSize, setRoadWidth, setUnit, splitBlock, splitInner, typicalPlot, type FieldLayout,
} from '@/shared/fieldLayout'

const METER = UNIT_PRESETS[0]!

/** 2 行 3 列区块，每块 6 m × 13.3 m，里面 2 行 3 列小区块；给小区块编上 id，模拟保存过 */
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
const rect = (l: FieldLayout, id: string) => blockRect(l, block(l, id))
const roads = (l: FieldLayout) => l.blocks.filter((b) => b.kind === 'ROAD')

describe('createLayout', () => {
  it('lays blocks out in rows and columns', () => {
    const l = createLayout({ blockRows: 2, blockCols: 3, blockW: 6, blockH: 13.3, unit: METER, innerRows: 2, innerCols: 3, roadW: 0 })
    expect(l.blocks).toHaveLength(6)
    expect(fieldSize(l)).toEqual({ w: 18, h: 26.6 })
    expect(plotCount(l)).toBe(36)
    expect(rect(l, 'B5')).toEqual({ x: 6, y: 13.3, w: 6, h: 13.3 })
    expect(checkLayout(l)).toBeNull()
  })

  it('leaves roads between blocks, each road running the whole way', () => {
    const l = saved(1.5)
    expect(fieldSize(l)).toEqual({ w: 21, h: 28.1 })
    expect(l.blocks.filter((b) => b.kind === 'PLANT')).toHaveLength(6)
    expect(rect(l, 'B2')).toEqual({ x: 7.5, y: 0, w: 6, h: 13.3 })
    // 一条横向道路、两条纵向道路，交叉着通到头
    expect(roads(l).map((b) => blockRect(l, b))).toEqual([
      { x: 0, y: 13.3, w: 21, h: 1.5 },
      { x: 6, y: 0, w: 1.5, h: 28.1 },
      { x: 13.5, y: 0, w: 1.5, h: 28.1 },
    ])
    expect(conflicts(l)).toEqual([])
    expect(checkLayout(l)).toBeNull()
    expect(defaultRoadWidth(l)).toBe(1.5)
  })

  it('can leave only horizontal or only vertical roads', () => {
    const l = createLayout({
      blockRows: 2, blockCols: 2, blockW: 6, blockH: 20, unit: METER, innerRows: 1, innerCols: 1, roadW: 1, rowRoadW: 2, colRoadW: 0,
    })
    expect(fieldSize(l)).toEqual({ w: 12, h: 42 })
    expect(roads(l).map((b) => blockRect(l, b))).toEqual([{ x: 0, y: 20, w: 12, h: 2 }])
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

  it('lists planting blocks top to bottom, left to right', () => {
    const l = moveBlocks(saved(), ['B1'], 0, 30)
    expect(plantBlocks(l).map((b) => b.id)).toEqual(['B2', 'B3', 'B4', 'B5', 'B6', 'B1'])
  })

  it('knows the plot size of a block and of the land', () => {
    const l = saved()
    expect(plotSize(l, block(l, 'B1'))).toEqual({ w: 2, h: 6.65 })
    expect(typicalPlot(l)).toEqual({ w: 2, h: 6.65 })
    expect(plotSize(l, block(setInnerTrackSize(l, 'B1', 'col', 0, 3), 'B1'))).toBeNull()
  })
})

describe('placing blocks freely', () => {
  it('moves blocks anywhere; the land grows to hold them', () => {
    const l = moveBlocks(saved(), ['B3', 'B6'], 2, 0)
    expect(rect(l, 'B3').x).toBe(14)
    expect(rect(l, 'B6').x).toBe(14)
    expect(fieldSize(l)).toEqual({ w: 20, h: 26.6 })
    expect(removedPlots(saved(), l)).toEqual([])
    expect(moveBlocks(saved(), ['B3'], 0, 0)).toEqual(saved())
  })

  it('shifts everything back in when a block is moved out past the left or top edge', () => {
    const l = moveBlocks(saved(), ['B1'], -3, -1)
    expect(rect(l, 'B1')).toEqual({ x: 0, y: 0, w: 6, h: 13.3 })
    expect(rect(l, 'B2')).toEqual({ x: 9, y: 1, w: 6, h: 13.3 })
    expect(fieldSize(l)).toEqual({ w: 21, h: 27.6 })
  })

  it('resizes a block; its plots scale with it', () => {
    const l = setBlockRect(saved(), 'B1', { x: 0, y: 0, w: 3, h: 13.3 })
    expect(innerSizes(l, block(l, 'B1')).cols).toEqual([1, 1, 1])
    expect(removedPlots(saved(), l)).toEqual([])
    // 太小的不行
    expect(setBlockRect(saved(), 'B1', { x: 0, y: 0, w: 0, h: 5 })).toEqual(saved())
  })

  it('adds a block, dividing it by the usual plot size', () => {
    const base = saved()
    const res = addBlock(base, { x: 20, y: 0, w: 8, h: 13.3 })
    const b = block(res.layout, res.id!)
    expect(b.name).toBe('区块7')
    expect(b.inner!.rows).toHaveLength(2)
    expect(b.inner!.cols).toHaveLength(4)
    expect(fieldSize(res.layout).w).toBe(28)
    // 还没有种植区块时用"新区块默认"
    const empty = removeBlocks(base, base.blocks.map((x) => x.id))
    const first = addBlock(empty, { x: 0, y: 0, w: 5, h: 5 })
    expect(block(first.layout, first.id!).inner!.cols).toHaveLength(3)
  })

  it('removes blocks with their plots, leaving the land as it was', () => {
    const l = removeBlocks(saved(), ['B1', 'B2'])
    expect(l.blocks).toHaveLength(4)
    expect(removedPlots(saved(), l)).toHaveLength(12)
    expect(fieldSize(l)).toEqual({ w: 18, h: 26.6 })
    expect(fitField(l)).toMatchObject({ w: 18, h: 26.6 })
    expect(fitField(removeBlocks(saved(), ['B1', 'B4']))).toMatchObject({ w: 12, h: 26.6 })
  })

  it('duplicates blocks with fresh plots', () => {
    const res = duplicateBlocks(saved(), ['B1'], 0, 30)
    expect(res.ids).toHaveLength(1)
    const copy = block(res.layout, res.ids[0]!)
    expect(copy.name).toBe('区块7')
    expect(blockRect(res.layout, copy)).toEqual({ x: 0, y: 30, w: 6, h: 13.3 })
    expect(copy.inner!.plots).toEqual({})
    expect(copy.inner!.rows).toHaveLength(2)
    expect(plotCount(res.layout)).toBe(42)
  })

  it('lays out an array of copies with gaps', () => {
    const one = createLayout({ blockRows: 1, blockCols: 1, blockW: 6, blockH: 10, unit: METER, innerRows: 2, innerCols: 3, roadW: 0 })
    const res = arrayBlocks(one, 'B1', 3, 2, 1, 2)
    expect(res.ids).toHaveLength(5)
    expect(res.layout.blocks.map((b) => [b.x, b.y])).toEqual([[0, 0], [7, 0], [14, 0], [0, 12], [7, 12], [14, 12]])
    expect(fieldSize(res.layout)).toEqual({ w: 20, h: 22 })
    expect(conflicts(res.layout)).toEqual([])
  })

  it('aligns and spreads blocks', () => {
    let l = moveBlocks(saved(), ['B2'], 0, 2)
    l = alignBlocks(l, ['B1', 'B2', 'B3'], 'top')
    expect(rect(l, 'B2').y).toBe(0)
    // 一个挨一个，中间隔 1
    l = distributeBlocks(saved(), ['B1', 'B2', 'B3'], 'col', 1)
    expect(['B1', 'B2', 'B3'].map((id) => rect(l, id).x)).toEqual([0, 7, 14])
    // 不给间隔：两头不动，中间匀开
    const spread = distributeBlocks(moveBlocks(saved(), ['B3'], 4, 0), ['B1', 'B2', 'B3'], 'col')
    expect(['B1', 'B2', 'B3'].map((id) => rect(spread, id).x)).toEqual([0, 8, 16])
    // 只选一个：向地块对齐
    expect(rect(alignBlocks(saved(), ['B1'], 'right'), 'B1').x).toBe(12)
  })

  it('changes the land size but never below what is on it', () => {
    expect(fieldSize(setFieldSize(saved(), 30, 40))).toEqual({ w: 30, h: 40 })
    expect(fieldSize(setFieldSize(saved(), 5, 5))).toEqual({ w: 18, h: 26.6 })
  })

  it('sets the block size from the plot size', () => {
    const l = setPlotSize(saved(), ['B1'], 1.5, null)
    expect(rect(l, 'B1')).toEqual({ x: 0, y: 0, w: 4.5, h: 13.3 })
    const both = setPlotSize(saved(), ['B6'], 3, 7)
    expect(rect(both, 'B6')).toEqual({ x: 12, y: 13.3, w: 9, h: 14 })
    expect(fieldSize(both)).toEqual({ w: 21, h: 27.3 })
  })

  it('reports blocks lying on top of each other, but lets roads cross', () => {
    const l = moveBlocks(saved(1), ['B1'], 3, 0)
    const hit = conflicts(l).map((p) => p.join('+'))
    expect(hit).toContain('B1+B2')
    expect(hit.some((p) => p.startsWith('B1+R'))).toBe(true)
    expect(checkLayout(l)).toContain('叠在一起')
    expect(conflicts(saved(1))).toEqual([])
  })
})

describe('merging blocks', () => {
  it('joins inner grids when they line up, keeping every plot', () => {
    const l = saved()
    const res = mergeBlocks(l, ['B1', 'B2'])
    expect(res.problem).toBeNull()
    expect(res.joined).toBe(true)
    expect(res.dropped).toEqual([])
    expect(res.anchorId).toBe('B1')
    const b1 = block(res.layout, 'B1')
    expect(blockRect(res.layout, b1)).toEqual({ x: 0, y: 0, w: 12, h: 13.3 })
    expect(b1.inner!.cols).toHaveLength(6)
    expect(res.layout.blocks).toHaveLength(5)
    expect(removedPlots(l, res.layout)).toEqual([])
    // B2 的第一个小区块（id 7）现在在合并后区块的第 0 行第 3 列，位置不变
    const moved = innerCells(res.layout, b1).find((c) => c.plotId === 7)!
    expect(moved.key).toBe('0,3')
    expect(moved.rect.x).toBeCloseTo(6)
  })

  it('swallows the road between two blocks when it is selected too', () => {
    const l = saved(1)
    const road = roads(l).find((b) => b.w === 1 && b.x === 6)!
    // 路没选上：隔着东西，不能合并
    expect(mergeBlocks(l, ['B1', 'B2']).problem).toContain('道路')
    // 只有这两块之间的一段路才能并进来：整条通到头的路超出了外框
    const short = setBlockRect(l, road.id, { x: 6, y: 0, w: 1, h: 13.3 })
    const res = mergeBlocks(short, ['B1', 'B2', road.id])
    expect(res.problem).toBeNull()
    expect(res.joined).toBe(true)
    expect(rect(res.layout, 'B1')).toEqual({ x: 0, y: 0, w: 13, h: 13.3 })
    expect(removedPlots(l, res.layout)).toEqual([])
    expect(res.layout.blocks.some((b) => b.id === road.id)).toBe(false)
  })

  it('drops plots of the other blocks when grids do not line up', () => {
    const l = resizeInner(saved(), 'B2', 3, 3)
    const res = mergeBlocks(l, ['B1', 'B2'])
    expect(res.joined).toBe(false)
    expect(res.dropped).toEqual([7, 8, 9, 10, 11, 12])
  })

  it('splits a block back along its plot lines, keeping every plot', () => {
    const merged = mergeBlocks(saved(), ['B1', 'B2', 'B4', 'B5']).layout
    expect(rect(merged, 'B1')).toEqual({ x: 0, y: 0, w: 12, h: 26.6 })
    const res = splitBlock(merged, 'B1', 2, 2)
    expect(res.problem).toBeNull()
    expect(res.ids).toHaveLength(4)
    expect(res.layout.blocks.filter((b) => b.kind === 'PLANT')).toHaveLength(6)
    expect(removedPlots(saved(), res.layout)).toEqual([])
    const back = res.layout.blocks.find((b) => b.x === 6 && b.y === 13.3)!
    expect(Object.values(back.inner!.plots).sort((a, b) => a - b)).toEqual([25, 26, 27, 28, 29, 30])
    expect(conflicts(res.layout)).toEqual([])
  })

  it('refuses to split across a merged plot or into more pieces than plots', () => {
    const l = mergeInner(saved(), 'B1', { r: 0, c: 0 }, { r: 1, c: 0 }).layout
    expect(splitBlock(l, 'B1', 2, 1).problem).toContain('合并')
    expect(splitBlock(saved(), 'B1', 3, 1).problem).toContain('2 行')
    expect(splitBlock(saved(), 'B1', 1, 1).problem).not.toBeNull()
    // 道路、空地按大小平分
    const road = roads(saved(1))[0]!
    const cut = splitBlock(saved(1), road.id, 1, 3)
    expect(cut.ids).toHaveLength(3)
    expect(rect(cut.layout, cut.ids[1]!)).toEqual({ x: 6.6667, y: 13.3, w: 6.6667, h: 1 })
  })

  it('expands a selection to whole merged areas', () => {
    expect(expandRect([{ r: 0, c: 1, rs: 2, cs: 2 }], { r: 1, c: 0 }, { r: 1, c: 1 })).toEqual({ r: 0, c: 0, rs: 2, cs: 3 })
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
})

describe('kinds and units', () => {
  it('turns a block into a road and back', () => {
    const road = setBlockKind(saved(), ['B3'], 'ROAD')
    expect(block(road, 'B3').inner).toBeNull()
    expect(block(road, 'B3').name).toBe('道路')
    // 位置和大小不变
    expect(rect(road, 'B3')).toEqual({ x: 12, y: 0, w: 6, h: 13.3 })
    const back = setBlockKind(road, ['B3'], 'PLANT')
    expect(innerCells(back, block(back, 'B3'))).toHaveLength(6)
    expect(block(back, 'B3').name).toBe('区块7')
  })

  it('converts sizes when changing the unit', () => {
    const chi = UNIT_PRESETS.find((u) => u.name === '尺')!
    const l = setUnit(saved(), chi, true)
    expect(fieldSize(l)).toEqual({ w: 54, h: 79.8 })
    expect(rect(l, 'B2')).toEqual({ x: 18, y: 0, w: 18, h: 39.9 })
    expect(fieldSize(setUnit(saved(), chi, false))).toEqual({ w: 18, h: 26.6 })
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

describe('cell kinds', () => {
  it('sets kinds on selected cells and clears them back to planting', () => {
    let l = setCellKinds(saved(), 'B1', ['0,0', '0,1'], 'ROAD')
    expect(block(l, 'B1').inner!.kinds).toEqual({ '0,0': 'ROAD', '0,1': 'ROAD' })
    // 小区块还在
    expect(removedPlots(saved(), l)).toEqual([])
    l = setCellKinds(l, 'B1', ['0,0', '0,1'], 'PLANT')
    expect(block(l, 'B1').inner!.kinds).toBeUndefined()
  })

  it('keeps the top-left kind when merging and spreads it when splitting', () => {
    const l = setCellKinds(saved(), 'B1', ['0,0'], 'GUARD')
    const merged = mergeInner(l, 'B1', { r: 0, c: 0 }, { r: 0, c: 2 }).layout
    expect(block(merged, 'B1').inner!.kinds).toEqual({ '0,0': 'GUARD' })
    const split = splitInner(merged, 'B1', 0, 0)
    expect(block(split, 'B1').inner!.kinds).toEqual({ '0,0': 'GUARD', '0,1': 'GUARD', '0,2': 'GUARD' })
  })

  it('forgets kinds of cells that are gone and carries them into merged and split blocks', () => {
    const l = setCellKinds(saved(), 'B1', ['1,2', '0,0'], 'BLANK')
    expect(block(resizeInner(l, 'B1', 1, 2), 'B1').inner!.kinds).toEqual({ '0,0': 'BLANK' })
    const m = setCellKinds(saved(), 'B2', ['0,0'], 'ROAD')
    const joined = mergeBlocks(m, ['B1', 'B2'])
    expect(joined.joined).toBe(true)
    expect(block(joined.layout, 'B1').inner!.kinds).toEqual({ '0,3': 'ROAD' })
    const back = splitBlock(joined.layout, 'B1', 1, 2)
    const b2 = back.layout.blocks.find((b) => b.x === 6 && b.y === 0)!
    expect(b2.inner!.kinds).toEqual({ '0,0': 'ROAD' })
  })
})

describe('regrid', () => {
  it('re-divides a block and keeps plots where they physically were', () => {
    // B1：2 行 3 列，小区块 1..6。重新分成 2 行 6 列：原来的 6 个小区块都留下，各占一个新格子，多出 6 个新格子
    const r = regridInner(saved(), 'B1', 2, 6)
    const inner = block(r.layout, 'B1').inner!
    expect(inner.rows).toHaveLength(2)
    expect(inner.cols).toHaveLength(6)
    expect(r.dropped).toEqual([])
    expect(inner.plots).toEqual({ '0,1': 1, '0,3': 2, '0,5': 3, '1,1': 4, '1,3': 5, '1,5': 6 })
  })

  it('drops plots that fall into the same new cell', () => {
    const r = regridInner(saved(), 'B1', 1, 1)
    expect(block(r.layout, 'B1').inner!.plots).toEqual({ '0,0': 1 })
    expect(r.dropped.sort()).toEqual([2, 3, 4, 5, 6])
  })

  it('places plots of every merged block by position, even when grids do not line up', () => {
    // 两个区块行数不一样，拼不起来；按合并前的位置重新分 2 行 6 列
    const l = resizeInner(saved(), 'B2', 1, 3)
    const spots = plotSpots(l, ['B1', 'B2'])
    const m = mergeBlocks(l, ['B1', 'B2'])
    expect(m.joined).toBe(false)
    const r = regridInner(m.layout, 'B1', 2, 6, spots)
    expect(r.dropped).toEqual([])
    expect(Object.keys(block(r.layout, 'B1').inner!.plots)).toHaveLength(9)
  })

  it('regrids a merged block after joining', () => {
    const m = mergeBlocks(saved(), ['B1', 'B4'])
    const r = regridInner(m.layout, 'B1', 4, 3)
    expect(r.dropped).toEqual([])
    expect(Object.keys(block(r.layout, 'B1').inner!.plots)).toHaveLength(12)
  })
})

describe('drawing roads', () => {
  it('drops a road on open ground exactly where it is drawn', () => {
    // 区块之间本来留着 1.5 的空当
    const base = removeBlocks(saved(1.5), roads(saved(1.5)).map((b) => b.id))
    const res = drawRoad(base, { x: 6, y: 0, w: 1.5, h: 28.1 }, 'col')
    expect(res.problem).toBeNull()
    expect(res.cut).toBe(0)
    expect(res.moved).toBe(0)
    expect(res.grew).toBe(0)
    expect(rect(res.layout, res.id!)).toEqual({ x: 6, y: 0, w: 1.5, h: 28.1 })
    expect(rect(res.layout, 'B2')).toEqual(rect(base, 'B2'))
    expect(conflicts(res.layout)).toEqual([])
  })

  it('pushes blocks apart instead of squeezing them: the land grows', () => {
    const l = saved()
    // 沿第 1、2 列区块之间的线画一条通到头的纵向道路
    const res = drawRoad(l, { x: 5.25, y: 0, w: 1.5, h: 26.6 }, 'col')
    expect(res.problem).toBeNull()
    expect(res.cut).toBe(0)
    expect(res.moved).toBe(4)
    expect(res.grew).toBe(1.5)
    expect(fieldSize(res.layout)).toEqual({ w: 19.5, h: 26.6 })
    expect(rect(res.layout, res.id!)).toEqual({ x: 6, y: 0, w: 1.5, h: 26.6 })
    // 前面的不动，后面的往后挪一个路宽，大小都不变
    expect(rect(res.layout, 'B1')).toEqual({ x: 0, y: 0, w: 6, h: 13.3 })
    expect(rect(res.layout, 'B2')).toEqual({ x: 7.5, y: 0, w: 6, h: 13.3 })
    expect(rect(res.layout, 'B6')).toEqual({ x: 13.5, y: 13.3, w: 6, h: 13.3 })
    expect(removedPlots(l, res.layout)).toEqual([])
    expect(conflicts(res.layout)).toEqual([])
  })

  it('takes up the free gap beside a block before pushing anything', () => {
    // 区块之间留着 1.5 的空当（没有道路）
    const base = removeBlocks(saved(1.5), roads(saved(1.5)).map((b) => b.id))
    // 点在第 1 列区块的右边（x = 6）上：后面正好有 1.5 的空当，路放进去，什么都不动
    const fits = drawRoad(base, { x: 5.25, y: 0, w: 1.5, h: 28.1 }, 'col')
    expect(rect(fits.layout, fits.id!)).toEqual({ x: 6, y: 0, w: 1.5, h: 28.1 })
    expect(fits.moved).toBe(0)
    expect(fits.grew).toBe(0)
    expect(rect(fits.layout, 'B2')).toEqual(rect(base, 'B2'))
    // 点在第 2 列区块的左边（x = 7.5）上：空当在前面，路放在前面
    const before = drawRoad(base, { x: 6.75, y: 0, w: 1.5, h: 28.1 }, 'col')
    expect(rect(before.layout, before.id!)).toEqual({ x: 6, y: 0, w: 1.5, h: 28.1 })
    expect(before.moved).toBe(0)
    // 路比空当宽：差多少，后面的往后顶多少
    const wide = drawRoad(base, { x: 5, y: 0, w: 2, h: 28.1 }, 'col')
    expect(rect(wide.layout, wide.id!)).toEqual({ x: 6, y: 0, w: 2, h: 28.1 })
    expect(rect(wide.layout, 'B2').x).toBe(8)
    expect(rect(wide.layout, 'B3').x).toBe(15.5)
    expect(wide.grew).toBe(0.5)
    expect(conflicts(wide.layout)).toEqual([])
    // 画在地块最左边：前面什么都没有，地块往左变大，区块都没被压
    const edge = drawRoad(saved(), { x: -0.5, y: 0, w: 1, h: 26.6 }, 'col')
    expect(rect(edge.layout, edge.id!)).toEqual({ x: 0, y: 0, w: 1, h: 26.6 })
    expect(rect(edge.layout, 'B1').x).toBe(1)
    expect(fieldSize(edge.layout).w).toBe(19)
    expect(conflicts(edge.layout)).toEqual([])
  })

  it('cuts blocks at the nearest plot line and keeps every plot its size', () => {
    const l = saved()
    // 横着穿过第一行三个区块的中间（小区块第 1、2 行之间在 y = 6.65）
    const res = drawRoad(l, { x: 0, y: 6, w: 18, h: 1 }, 'row')
    expect(res.problem).toBeNull()
    expect(res.cut).toBe(3)
    expect(res.grew).toBe(1)
    expect(rect(res.layout, res.id!)).toEqual({ x: 0, y: 6.65, w: 18, h: 1 })
    expect(rect(res.layout, 'B1')).toEqual({ x: 0, y: 0, w: 6, h: 6.65 })
    expect(block(res.layout, 'B1').inner!.plots).toEqual({ '0,0': 1, '0,1': 2, '0,2': 3 })
    // 切下来的下半块是新区块，带着原来的小区块，挪到路后面
    const lower = res.layout.blocks.find((b) => b.kind === 'PLANT' && b.x === 0 && b.y === 7.65)!
    expect(lower.h).toBe(6.65)
    expect(lower.inner!.plots).toEqual({ '0,0': 4, '0,1': 5, '0,2': 6 })
    expect(lower.name).toBe('区块7')
    // 下面一行区块跟着往下挪
    expect(rect(res.layout, 'B4').y).toBe(14.3)
    expect(fieldSize(res.layout)).toEqual({ w: 18, h: 27.6 })
    expect(removedPlots(l, res.layout)).toEqual([])
    expect(conflicts(res.layout)).toEqual([])
  })

  it('lengthens the roads it crosses', () => {
    const l = saved(1)
    const vertical = roads(l).find((b) => b.h > b.w)!
    expect(vertical.h).toBe(27.6)
    const res = drawRoad(l, { x: 0, y: 6.15, w: 20, h: 1 }, 'row')
    expect(res.cut).toBe(3)
    expect(block(res.layout, vertical.id).h).toBe(28.6)
    // 原来的横向道路跟着往下挪
    const horizontal = roads(l).find((b) => b.w > b.h)!
    expect(block(res.layout, horizontal.id).y).toBe(14.3)
    expect(conflicts(res.layout)).toEqual([])
  })

  it('handles a road that only runs part of the way', () => {
    const l = saved(1)
    // 只穿过左上角的区块：只有这一块切开，下半块往下挪；它顶到下面那条通到头的横向道路，
    // 道路整条往下让，道路下面的区块也跟着让。旁边没被穿过的区块不动
    const res = drawRoad(l, { x: 0, y: 6.15, w: 6, h: 1 }, 'row')
    expect(res.cut).toBe(1)
    expect(rect(res.layout, res.id!)).toEqual({ x: 0, y: 6.65, w: 6, h: 1 })
    expect(rect(res.layout, 'B1').h).toBe(6.65)
    expect(rect(res.layout, 'B2')).toEqual(rect(l, 'B2'))
    expect(rect(res.layout, 'B3')).toEqual(rect(l, 'B3'))
    const horizontal = roads(l).find((b) => b.w > b.h)!
    expect(block(res.layout, horizontal.id).y).toBe(14.3)
    expect(rect(res.layout, 'B4').y).toBe(15.3)
    expect(rect(res.layout, 'B5').y).toBe(15.3)
    expect(removedPlots(l, res.layout)).toEqual([])
    expect(conflicts(res.layout)).toEqual([])
    // 原来通到地块下边的纵向道路，地块变长了跟着通到新的下边
    expect(fieldSize(res.layout).h).toBe(28.6)
    expect(roads(res.layout).filter((b) => b.h > b.w).map((b) => b.h)).toEqual([28.6, 28.6])

    // 下面没有通到头的东西挡着时，只挪这一列
    const open = drawRoad(saved(), { x: 0, y: 6.15, w: 6, h: 1 }, 'row')
    expect(rect(open.layout, 'B4').y).toBe(14.3)
    expect(rect(open.layout, 'B5').y).toBe(13.3)
    expect(conflicts(open.layout)).toEqual([])
  })

  it('stretches or pulls back the ends that land inside a block', () => {
    const l = saved()
    // 右头伸进第 3 列区块一点点：缩回到第 2、3 列之间
    const short = drawRoad(l, { x: 0, y: 6.15, w: 13, h: 1 }, 'row')
    expect(rect(short.layout, short.id!)).toEqual({ x: 0, y: 6.65, w: 12, h: 1 })
    expect(short.cut).toBe(2)
    // 伸进去一大半：延长到头
    const long = drawRoad(l, { x: 0, y: 6.15, w: 16, h: 1 }, 'row')
    expect(rect(long.layout, long.id!)).toEqual({ x: 0, y: 6.65, w: 18, h: 1 })
    expect(long.cut).toBe(3)
    // 只在一个区块里面画了一小段：通到这个区块两边
    const inside = drawRoad(l, { x: 1, y: 6.15, w: 2, h: 1 }, 'row')
    expect(rect(inside.layout, inside.id!)).toEqual({ x: 0, y: 6.65, w: 6, h: 1 })
  })

  it('moves a block with no line to cut along wholly behind the road', () => {
    const l = setBlockKind(saved(), ['B1'], 'GUARD')
    const res = drawRoad(l, { x: 0, y: 2, w: 6, h: 1 }, 'row')
    expect(res.cut).toBe(0)
    // 离上边最近：路放在最上面，保护区整个往下挪
    expect(rect(res.layout, res.id!)).toEqual({ x: 0, y: 0, w: 6, h: 1 })
    expect(rect(res.layout, 'B1').y).toBe(1)
    expect(rect(res.layout, 'B4').y).toBe(14.3)
  })

  it('does not cut through a merged plot', () => {
    const l = mergeInner(saved(), 'B1', { r: 0, c: 0 }, { r: 1, c: 0 }).layout
    expect(blockSeams(l, block(l, 'B1'), 'row')).toEqual([0, 13.3])
    expect(blockSeams(l, block(l, 'B1'), 'col')).toEqual([0, 2, 4, 6])
  })

  it('removes a road and closes the gap when nothing is in the way', () => {
    const l = saved(1.5)
    const vertical = roads(l).find((b) => b.x === 6)!
    const res = removeRoad(l, vertical.id, true)
    expect(res.closed).toBe(true)
    expect(fieldSize(res.layout)).toEqual({ w: 19.5, h: 28.1 })
    expect(rect(res.layout, 'B2').x).toBe(6)
    // 另一条纵向道路和它后面的区块也跟着收回来
    expect(rect(res.layout, 'B3').x).toBe(13.5)
    // 横向道路跟着变短
    expect(roads(res.layout).find((b) => b.w > b.h)!.w).toBe(19.5)
    expect(removedPlots(l, res.layout)).toEqual([])
    // 只删路：别的都不动
    const plain = removeRoad(l, vertical.id, false)
    expect(plain.closed).toBe(false)
    expect(rect(plain.layout, 'B2')).toEqual(rect(l, 'B2'))
    expect(fieldSize(plain.layout)).toEqual(fieldSize(l))
  })

  it('widens and narrows a road, moving what is behind it', () => {
    const l = saved(1)
    const vertical = roads(l).find((b) => b.x === 6)!
    const wide = setRoadWidth(l, vertical.id, 3)
    expect(block(wide, vertical.id).w).toBe(3)
    expect(rect(wide, 'B1')).toEqual(rect(l, 'B1'))
    expect(rect(wide, 'B2').x).toBe(9)
    expect(rect(wide, 'B3').x).toBe(16)
    expect(fieldSize(wide).w).toBe(22)
    expect(roads(wide).find((b) => b.w > b.h)!.w).toBe(22)
    expect(conflicts(wide)).toEqual([])
    const narrow = setRoadWidth(wide, vertical.id, 1)
    expect(rect(narrow, 'B2').x).toBe(7)
    expect(fieldSize(narrow).w).toBe(20)
    expect(removedPlots(l, narrow)).toEqual([])
  })
})
