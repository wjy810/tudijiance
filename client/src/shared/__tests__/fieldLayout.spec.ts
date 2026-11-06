import { describe, expect, it } from 'vitest'

import {
  UNIT_PRESETS, alignBlockFit, anchors, blockRect, canMoveCells, createLayout, expandRect, fieldSize, innerCells, innerSizes,
  insertTrack, mergeBlocks, mergeInner, moveBlockFit, moveCells, plotCount, removeTrack, removedPlots, resizeBlockGrid, resizeInner,
  plotPlace, plotPlaces, setBlockFit, setBlockKind, setInnerTrackPair, setInnerTrackSize, setTrackPair, setTrackSize, setUnit,
  plotSpots, regridInner, setCellKinds, splitBlock, splitInner, swapBlocks, type FieldLayout,
  addRoad, addRoadProblem, defaultRoadWidth, isRoadTrack, removeRoad, roadOfBlock, roadSegments, roadToBlocks, trackToRoad,
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

describe('cell kinds', () => {
  it('sets kinds on selected cells and clears them back to planting', () => {
    let l = setCellKinds(saved(), 'B1', ['0,0', '0,1'], 'ROAD')
    expect(block(l, 'B1').inner!.kinds).toEqual({ '0,0': 'ROAD', '0,1': 'ROAD' })
    // 小区还在
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

  it('forgets kinds of cells that are gone and carries them into merged blocks', () => {
    const l = setCellKinds(saved(), 'B1', ['1,2', '0,0'], 'BLANK')
    expect(block(resizeInner(l, 'B1', 1, 2), 'B1').inner!.kinds).toEqual({ '0,0': 'BLANK' })
    const m = setCellKinds(saved(), 'B2', ['0,0'], 'ROAD')
    const joined = mergeBlocks(m, { r: 0, c: 0 }, { r: 0, c: 1 })
    expect(joined.joined).toBe(true)
    expect(block(joined.layout, 'B1').inner!.kinds).toEqual({ '0,3': 'ROAD' })
    const back = splitBlock(joined.layout, 'B1')
    const b2 = back.blocks.find((b) => b.r === 0 && b.c === 1)!
    expect(b2.inner!.kinds).toEqual({ '0,0': 'ROAD' })
  })
})

describe('regrid', () => {
  it('re-divides a block and keeps plots where they physically were', () => {
    // B1：2 行 3 列，小区 1..6。重新分成 2 行 6 列：原来的 6 个小区都留下，各占一个新格子，多出 6 个新格子
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
    // 两个区块中间隔着道路，拼不起来；按合并前的位置重新分 2 行 7 列
    const l = saved(1)
    const ids = ['B1', 'B2']
    const spots = plotSpots(l, ids)
    const m = mergeBlocks(l, { r: 0, c: 0 }, { r: 0, c: 2 })
    expect(m.joined).toBe(false)
    const r = regridInner(m.layout, 'B1', 2, 13, spots)
    expect(r.dropped).toEqual([])
    expect(Object.keys(block(r.layout, 'B1').inner!.plots)).toHaveLength(12)
  })

  it('regrids a merged block after joining', () => {
    const m = mergeBlocks(saved(), { r: 0, c: 0 }, { r: 1, c: 0 })
    const r = regridInner(m.layout, 'B1', 4, 3)
    expect(r.dropped).toEqual([])
    expect(Object.keys(block(r.layout, 'B1').inner!.plots)).toHaveLength(12)
  })
})

describe('roads', () => {
  it('builds separate horizontal and vertical roads', () => {
    const l = createLayout({
      blockRows: 2, blockCols: 2, blockW: 6, blockH: 20, unit: METER, innerRows: 1, innerCols: 1, roadW: 1, rowRoadW: 2, colRoadW: 0,
    })
    expect(l.rows).toEqual([20, 2, 20])
    expect(l.cols).toEqual([6, 6])
    expect(isRoadTrack(l, 'row', 1)).toBe(true)
    expect(isRoadTrack(l, 'col', 0)).toBe(false)
  })

  it('makes each vertical road one strip when there are no horizontal roads', () => {
    const l = createLayout({
      blockRows: 2, blockCols: 2, blockW: 6, blockH: 20, unit: METER, innerRows: 1, innerCols: 1, roadW: 0, colRoadW: 1,
    })
    expect(l.cols).toEqual([6, 1, 6])
    expect(l.blocks.filter((b) => b.kind === 'ROAD')).toHaveLength(1)
    expect(l.merges).toEqual([{ r: 0, c: 1, rs: 2, cs: 1 }])
    expect(roadSegments(l, 'col', 1)).toHaveLength(1)
    expect(anchors(l.rows.length, l.cols.length, l.merges)).toHaveLength(l.blocks.length)
  })

  it('adds a road without squeezing the blocks: the land grows', () => {
    const l = saved()
    const next = addRoad(l, 'col', 1, 1.5)
    expect(next.cols).toEqual([6, 1.5, 6, 6])
    expect(fieldSize(next).w).toBe(19.5)
    expect(isRoadTrack(next, 'col', 1)).toBe(true)
    // 区块和小区都还在，跟着挪
    expect(block(next, 'B2').c).toBe(2)
    expect(removedPlots(l, next)).toEqual([])
    // 纵向道路是一条（合并起来）
    const roads = next.blocks.filter((b) => b.kind === 'ROAD')
    expect(roads).toHaveLength(1)
    expect(next.merges).toContainEqual({ r: 0, c: 1, rs: 2, cs: 1 })
  })

  it('crosses existing roads', () => {
    const l = createLayout({
      blockRows: 2, blockCols: 2, blockW: 6, blockH: 20, unit: METER, innerRows: 1, innerCols: 1, roadW: 0, rowRoadW: 1,
    })
    // 已有横向道路（第 1 行），在两列区块之间加纵向道路：横向道路跟着变长，纵向道路被它隔成两段
    const next = addRoad(l, 'col', 1, 1)
    expect(next.cols).toEqual([6, 1, 6])
    expect(isRoadTrack(next, 'col', 1)).toBe(true)
    expect(isRoadTrack(next, 'row', 1)).toBe(true)
    expect(next.merges).toContainEqual({ r: 1, c: 0, rs: 1, cs: 3 })
    expect(roadSegments(next, 'col', 1)).toHaveLength(2)
    expect(roadSegments(next, 'row', 1)).toHaveLength(1)
  })

  it('refuses a road next to a road or through a merged block', () => {
    const l = saved(1)
    expect(addRoadProblem(l, 'row', 1)).toBe('旁边已经是道路了')
    const merged = mergeBlocks(saved(), { r: 0, c: 0 }, { r: 1, c: 0 }).layout
    expect(addRoadProblem(merged, 'row', 1)).toContain('合并')
    expect(addRoad(merged, 'row', 1, 1)).toBe(merged)
    expect(addRoadProblem(merged, 'col', 1)).toBeNull()
  })

  it('removes a whole road: the land shrinks', () => {
    const l = saved(1.5)
    const next = removeRoad(l, 'col', 1)
    expect(next.cols).toEqual([6, 6, 1.5, 6])
    expect(removedPlots(l, next)).toEqual([])
    // 不是整条道路的不删
    expect(removeRoad(l, 'col', 0)).toBe(l)
  })

  it('narrows a whole row of blocks turned into road, and widens it back', () => {
    const l = saved(1)
    expect(defaultRoadWidth(l)).toBe(1)
    // 第 2 列整列（两个区块）改成道路：收窄成道路宽，并成一条
    const ids = l.blocks.filter((b) => b.kind === 'PLANT' && b.c === 2).map((b) => b.id)
    const road = setBlockKind(l, ids, 'ROAD')
    expect(road.cols).toEqual([6, 1, 1, 1, 6])
    expect(isRoadTrack(road, 'col', 2)).toBe(true)
    // 再改回来：按旁边区块的宽，横向道路交叉的地方还是道路
    // 只改一段：还是一段窄的种植区块，不动整列
    const seg = roadSegments(road, 'col', 2)
    expect(seg).toHaveLength(2)
    expect(setBlockKind(road, [seg[0]!], 'PLANT').cols).toEqual([6, 1, 1, 1, 6])
    expect(roadOfBlock(road, seg[0]!)).toEqual({ axis: 'col', index: 2 })
    // 两段都选上：整条改回来
    const back = setBlockKind(road, seg, 'PLANT')
    expect(back.cols).toEqual([6, 1, 6, 1, 6])
    expect(back.blocks.filter((b) => b.c === 2 && b.kind === 'PLANT')).toHaveLength(2)
    expect(isRoadTrack(back, 'row', 1)).toBe(true)
  })

  it('keeps the road width when a road becomes a guard strip', () => {
    const l = saved(1)
    const road = l.blocks.find((b) => b.kind === 'ROAD' && b.r === 1)!
    const guard = setBlockKind(l, [road.id], 'GUARD')
    expect(guard.rows).toEqual([13.3, 1, 13.3])
    expect(block(guard, road.id).kind).toBe('GUARD')
  })

  it('turns a whole horizontal road back into blocks', () => {
    const l = saved(1)
    const back = roadToBlocks(l, 'row', 1)
    expect(back.rows).toEqual([13.3, 13.3, 13.3])
    // 横向道路那一行变成区块，和纵向道路交叉的格子还是道路
    expect(back.blocks.filter((b) => b.r === 1 && b.kind === 'PLANT')).toHaveLength(3)
    expect(back.blocks.filter((b) => b.r === 1 && b.kind === 'ROAD')).toHaveLength(2)
    expect(isRoadTrack(back, 'col', 1)).toBe(true)
    // 新区块接着"区块几"往下数，不跳号（道路也占编号）
    expect(back.blocks.filter((b) => b.r === 1 && b.kind === 'PLANT').map((b) => b.name)).toEqual(['区块7', '区块8', '区块9'])
  })

  it('turns a track into a road and drops its plots', () => {
    const l = saved()
    const next = trackToRoad(l, 'row', 0, 2)
    expect(next.rows).toEqual([2, 13.3])
    expect(isRoadTrack(next, 'row', 0)).toBe(true)
    expect(removedPlots(l, next)).toHaveLength(18)
  })
})
