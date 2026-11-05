// 实验田的区块布局：实验田 → 区块 → 小区两层网格。和服务器 FieldLayout.java 的字段一致。
//
//   区块网格  rows（每行区块的高）× cols（每列区块的宽），单位见 unit，可以合并（merges）
//   区块      PLANT 种植区块里再分小区网格（inner）；ROAD 道路、GUARD 保护区、BLANK 空地没有小区
//   特殊区块  fit 比原来的位置（槽位）小时，槽位里其余部分是道路，区块可以在槽位里移动
//   小区网格  inner.rows / inner.cols 是相对大小，按区块实际大小分；可以合并；inner.plots 记格子对应的小区 id
//
// 这里都是纯函数：每个操作返回新的布局，不改原来的，方便撤销和测试。

export type BlockKind = 'PLANT' | 'ROAD' | 'GUARD' | 'BLANK'

export interface Span {
  r: number
  c: number
  rs: number
  cs: number
}

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export interface Unit {
  name: string
  symbol: string
  /** 1 个单位等于多少米 */
  meters: number
}

/** 区块里一格（小区）的类型：种植小区、道路（走道）、保护区、空地 */
export type CellKind = 'PLANT' | 'ROAD' | 'GUARD' | 'BLANK'

export interface Inner {
  rows: number[]
  cols: number[]
  merges: Span[]
  /** "行,列" → 小区 id；新加的格子没有，保存后服务器补上 */
  plots: Record<string, number>
  /** "行,列" → 格子类型；没写的是种植小区 */
  kinds?: Record<string, CellKind>
}

export interface LayoutBlock {
  id: string
  r: number
  c: number
  name: string
  kind: BlockKind
  fit: Rect | null
  inner: Inner | null
}

export interface FieldLayout {
  version: 1
  unit: Unit
  rows: number[]
  cols: number[]
  merges: Span[]
  blocks: LayoutBlock[]
  nextBlockNo: number
  defaultInner: { rows: number; cols: number } | null
  /** 真北方向：平面图上方顺时针转多少度是北 */
  northDeg: number
}

export const BLOCK_KIND: Record<BlockKind, string> = {
  PLANT: '种植区块',
  ROAD: '道路',
  GUARD: '保护区',
  BLANK: '空地',
}

export const CELL_KIND: Record<CellKind, string> = {
  PLANT: '种植小区',
  ROAD: '道路',
  GUARD: '保护区',
  BLANK: '空地',
}

/** 格子类型，没写的是种植小区 */
export function cellKind(inner: Inner | null | undefined, key: string): CellKind {
  return inner?.kinds?.[key] ?? 'PLANT'
}

export const UNIT_PRESETS: Unit[] = [
  { name: '米', symbol: 'm', meters: 1 },
  { name: '厘米', symbol: 'cm', meters: 0.01 },
  { name: '尺', symbol: '尺', meters: 1 / 3 },
  { name: '步', symbol: '步', meters: 5 / 3 },
  { name: '丈', symbol: '丈', meters: 10 / 3 },
  { name: '英尺', symbol: 'ft', meters: 0.3048 },
]

export const MAX_BLOCK_TRACKS = 50
export const MAX_INNER_TRACKS = 60
export const MAX_PLOTS = 3000
const EPS = 1e-6

// ---------- 小工具 ----------

export const keyOf = (r: number, c: number) => `${r},${c}`

export function parseKey(key: string): [number, number] {
  const [r, c] = key.split(',').map(Number)
  return [r!, c!]
}

export const sum = (a: number[], from = 0, to = a.length) => {
  let s = 0
  for (let i = from; i < to; i++) s += a[i]!
  return s
}

export const round = (v: number, d = 3) => Math.round(v * 10 ** d) / 10 ** d
const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi))
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T

/** 一个数加上单位：12.5 m、3 尺 */
export function fmt(v: number, unit: Unit, digits = 2): string {
  return `${round(v, digits)} ${unit.symbol}`
}

/** 网格的所有格子（合并的按锚点算一个），按行、列排序 */
export function anchors(rows: number, cols: number, merges: Span[]): Span[] {
  const covered = new Set<string>()
  const out: Span[] = merges.map((m) => ({ ...m }))
  for (const m of merges) {
    for (let r = m.r; r < m.r + m.rs; r++) for (let c = m.c; c < m.c + m.cs; c++) covered.add(keyOf(r, c))
  }
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) if (!covered.has(keyOf(r, c))) out.push({ r, c, rs: 1, cs: 1 })
  }
  return out.sort((a, b) => a.r - b.r || a.c - b.c)
}

/** 盖住 (r, c) 的合并区域 */
export function mergeAt(merges: Span[], r: number, c: number): Span | undefined {
  return merges.find((m) => r >= m.r && r < m.r + m.rs && c >= m.c && c < m.c + m.cs)
}

/** 锚点 (r, c) 的范围 */
export function spanOf(merges: Span[], r: number, c: number): Span {
  return merges.find((m) => m.r === r && m.c === c) ?? { r, c, rs: 1, cs: 1 }
}

/** 两个格子围成的矩形，再扩到把碰到的合并区域整个包进来 */
export function expandRect(merges: Span[], a: { r: number; c: number }, b: { r: number; c: number }): Span {
  let r0 = Math.min(a.r, b.r)
  let r1 = Math.max(a.r, b.r)
  let c0 = Math.min(a.c, b.c)
  let c1 = Math.max(a.c, b.c)
  let changed = true
  while (changed) {
    changed = false
    for (const m of merges) {
      const hit = m.r <= r1 && m.r + m.rs - 1 >= r0 && m.c <= c1 && m.c + m.cs - 1 >= c0
      if (!hit) continue
      if (m.r < r0) { r0 = m.r; changed = true }
      if (m.c < c0) { c0 = m.c; changed = true }
      if (m.r + m.rs - 1 > r1) { r1 = m.r + m.rs - 1; changed = true }
      if (m.c + m.cs - 1 > c1) { c1 = m.c + m.cs - 1; changed = true }
    }
  }
  return { r: r0, c: c0, rs: r1 - r0 + 1, cs: c1 - c0 + 1 }
}

const inside = (s: Span, r: number, c: number) => r >= s.r && r < s.r + s.rs && c >= s.c && c < s.c + s.cs
const containsSpan = (outer: Span, s: Span) =>
  s.r >= outer.r && s.c >= outer.c && s.r + s.rs <= outer.r + outer.rs && s.c + s.cs <= outer.c + outer.cs

// ---------- 几何 ----------

export function fieldSize(l: FieldLayout): { w: number; h: number } {
  return { w: sum(l.cols), h: sum(l.rows) }
}

/** 网格里一个范围的矩形 */
export function trackRect(rows: number[], cols: number[], s: Span): Rect {
  return { x: sum(cols, 0, s.c), y: sum(rows, 0, s.r), w: sum(cols, s.c, s.c + s.cs), h: sum(rows, s.r, s.r + s.rs) }
}

export function blockSpan(l: FieldLayout, b: LayoutBlock): Span {
  return spanOf(l.merges, b.r, b.c)
}

/** 区块的槽位（原来的位置） */
export function blockSlot(l: FieldLayout, b: LayoutBlock): Rect {
  return trackRect(l.rows, l.cols, blockSpan(l, b))
}

/** 区块实际占的地方：特殊区块按 fit，否则就是槽位 */
export function blockRect(l: FieldLayout, b: LayoutBlock): Rect {
  const s = blockSlot(l, b)
  return b.fit ? { x: s.x + b.fit.x, y: s.y + b.fit.y, w: b.fit.w, h: b.fit.h } : s
}

export interface CellGeom {
  key: string
  span: Span
  rect: Rect
  plotId: number | null
  kind: CellKind
}

/** 区块里的小区（格子），rect 是在整块地里的位置 */
export function innerCells(l: FieldLayout, b: LayoutBlock): CellGeom[] {
  if (!b.inner) return []
  const box = blockRect(l, b)
  const { rows, cols, merges, plots } = b.inner
  const sx = box.w / sum(cols)
  const sy = box.h / sum(rows)
  return anchors(rows.length, cols.length, merges).map((s) => {
    const r = trackRect(rows, cols, s)
    const key = keyOf(s.r, s.c)
    return {
      key, span: s, plotId: plots[key] ?? null, kind: cellKind(b.inner, key),
      rect: { x: box.x + r.x * sx, y: box.y + r.y * sy, w: r.w * sx, h: r.h * sy },
    }
  })
}

/** 区块里每一行、每一列小区的实际大小（单位同布局） */
export function innerSizes(l: FieldLayout, b: LayoutBlock): { rows: number[]; cols: number[] } {
  if (!b.inner) return { rows: [], cols: [] }
  const box = blockRect(l, b)
  const rs = sum(b.inner.rows)
  const cs = sum(b.inner.cols)
  return { rows: b.inner.rows.map((v) => (v / rs) * box.h), cols: b.inner.cols.map((v) => (v / cs) * box.w) }
}

export function plotCount(l: FieldLayout): number {
  return l.blocks.reduce((n, b) => n + (b.inner ? anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges).length : 0), 0)
}

/** 布局里引用的所有小区 id */
export function plotIds(l: FieldLayout): Set<number> {
  const out = new Set<number>()
  for (const b of l.blocks) for (const id of Object.values(b.inner?.plots ?? {})) out.add(id)
  return out
}

/** 改布局后会去掉的小区 */
export function removedPlots(before: FieldLayout, after: FieldLayout): number[] {
  const keep = plotIds(after)
  return [...plotIds(before)].filter((id) => !keep.has(id))
}

/** 小区所在的区块和格子 */
export function locatePlot(l: FieldLayout, plotId: number): { block: LayoutBlock; key: string } | null {
  for (const b of l.blocks) {
    for (const [key, id] of Object.entries(b.inner?.plots ?? {})) if (id === plotId) return { block: b, key }
  }
  return null
}

/** 小区在哪：区块名称和在区块里的第几行第几列（合并的写跨几行几列）；找不到时 null */
export function plotPlace(l: FieldLayout | null | undefined, plotId: number): string | null {
  if (!l) return null
  const hit = locatePlot(l, plotId)
  if (!hit?.block.inner) return null
  const [r, c] = parseKey(hit.key)
  const s = spanOf(hit.block.inner.merges, r, c)
  const rows = s.rs > 1 ? `第 ${r + 1}–${r + s.rs} 行` : `第 ${r + 1} 行`
  const cols = s.cs > 1 ? `第 ${c + 1}–${c + s.cs} 列` : `第 ${c + 1} 列`
  return `${hit.block.name} · ${rows}${cols}`
}

/** 所有小区在哪（一次算好，平面图上每个小区的说明用） */
export function plotPlaces(l: FieldLayout | null | undefined): Map<number, string> {
  const out = new Map<number, string>()
  if (!l) return out
  for (const b of l.blocks) {
    for (const id of Object.values(b.inner?.plots ?? {})) {
      const p = plotPlace({ ...l, blocks: [b] }, id)
      if (p) out.set(id, p)
    }
  }
  return out
}

// ---------- 新建 ----------

export interface CreateOptions {
  blockRows: number
  blockCols: number
  /** 每个区块的宽和高 */
  blockW: number
  blockH: number
  unit: Unit
  /** 每个区块里几行几列小区 */
  innerRows: number
  innerCols: number
  /** 区块之间的道路宽，0 表示不留 */
  roadW: number
}

function evenInner(rows: number, cols: number): Inner {
  return { rows: Array(rows).fill(1), cols: Array(cols).fill(1), merges: [], plots: {} }
}

/** 初始化：几行几列区块、每块多宽多高、里面几行几列小区，区块之间可以留道路 */
export function createLayout(o: CreateOptions): FieldLayout {
  const road = o.roadW > 0
  const rows: number[] = []
  const cols: number[] = []
  for (let i = 0; i < o.blockRows; i++) {
    if (road && i > 0) rows.push(o.roadW)
    rows.push(o.blockH)
  }
  for (let j = 0; j < o.blockCols; j++) {
    if (road && j > 0) cols.push(o.roadW)
    cols.push(o.blockW)
  }
  const merges: Span[] = []
  const blocks: LayoutBlock[] = []
  let no = 1
  let roadNo = 1
  for (let r = 0; r < rows.length; r++) {
    const roadRow = road && r % 2 === 1
    if (roadRow) {
      // 横向道路整行合并成一条
      if (cols.length > 1) merges.push({ r, c: 0, rs: 1, cs: cols.length })
      blocks.push({ id: `R${roadNo++}`, r, c: 0, name: '道路', kind: 'ROAD', fit: null, inner: null })
      continue
    }
    for (let c = 0; c < cols.length; c++) {
      if (road && c % 2 === 1) {
        blocks.push({ id: `R${roadNo++}`, r, c, name: '道路', kind: 'ROAD', fit: null, inner: null })
      } else {
        blocks.push({
          id: `B${no}`, r, c, name: `区块${no}`, kind: 'PLANT', fit: null, inner: evenInner(o.innerRows, o.innerCols),
        })
        no++
      }
    }
  }
  return {
    version: 1, unit: { ...o.unit }, rows, cols, merges, blocks, nextBlockNo: Math.max(no, roadNo),
    defaultInner: { rows: o.innerRows, cols: o.innerCols }, northDeg: 0,
  }
}

function newBlock(l: FieldLayout, r: number, c: number, kind: BlockKind = 'PLANT'): LayoutBlock {
  const n = l.nextBlockNo++
  const d = l.defaultInner ?? { rows: 1, cols: 1 }
  return {
    id: `B${n}`, r, c, name: kind === 'PLANT' ? `区块${n}` : BLOCK_KIND[kind], kind, fit: null,
    inner: kind === 'PLANT' ? evenInner(d.rows, d.cols) : null,
  }
}

// ---------- 区块网格 ----------

export type Axis = 'row' | 'col'

/** 槽位变了以后修正特殊区块：超出就收小，正好填满就恢复成普通区块 */
function reconcileFits(l: FieldLayout) {
  for (const b of l.blocks) {
    if (!b.fit) continue
    const s = blockSlot(l, b)
    const w = Math.min(b.fit.w, s.w)
    const h = Math.min(b.fit.h, s.h)
    if (Math.abs(w - s.w) < EPS && Math.abs(h - s.h) < EPS) {
      b.fit = null
    } else {
      b.fit = { w, h, x: clamp(b.fit.x, 0, s.w - w), y: clamp(b.fit.y, 0, s.h - h) }
    }
  }
}

/** 改一行区块的高或一列区块的宽 */
export function setTrackSize(layout: FieldLayout, axis: Axis, index: number, size: number): FieldLayout {
  const l = clone(layout)
  const tracks = axis === 'row' ? l.rows : l.cols
  if (index < 0 || index >= tracks.length || !(size > 0)) return layout
  tracks[index] = round(size, 4)
  reconcileFits(l)
  return l
}

/**
 * 拖动区块之间的分界线：第 index 行（列）改成 a，下一行（列）改成 b，总大小不变。
 * b 为 null 表示拖的是地块最外边，只改这一行（列），地块跟着变大变小
 */
export function setTrackPair(layout: FieldLayout, axis: Axis, index: number, a: number, b: number | null): FieldLayout {
  const tracks = axis === 'row' ? layout.rows : layout.cols
  if (index < 0 || index >= tracks.length || !(a > 0)) return layout
  if (b != null && (index + 1 >= tracks.length || !(b > 0))) return layout
  const l = clone(layout)
  const t = axis === 'row' ? l.rows : l.cols
  t[index] = round(a, 4)
  if (b != null) t[index + 1] = round(b, 4)
  reconcileFits(l)
  return l
}

/** 在第 index 行（列）前面插入一行（列）区块；index 等于行数时加在最后 */
export function insertTrack(layout: FieldLayout, axis: Axis, index: number, size?: number): FieldLayout {
  const l = clone(layout)
  const tracks = axis === 'row' ? l.rows : l.cols
  if (tracks.length >= MAX_BLOCK_TRACKS) return layout
  const near = tracks[Math.min(index, tracks.length - 1)] ?? 1
  tracks.splice(index, 0, size ?? near)
  const pos = (s: { r: number; c: number }) => (axis === 'row' ? s.r : s.c)
  for (const m of l.merges) {
    if (pos(m) >= index) {
      if (axis === 'row') m.r++
      else m.c++
    } else if (pos(m) + (axis === 'row' ? m.rs : m.cs) > index) {
      // 插在合并区域中间：合并区域跟着变大
      if (axis === 'row') m.rs++
      else m.cs++
    }
  }
  for (const b of l.blocks) {
    if (pos(b) >= index) {
      if (axis === 'row') b.r++
      else b.c++
    }
  }
  // 新的一行（列）里没被合并区域盖住的格子，各建一个区块
  const other = axis === 'row' ? l.cols.length : l.rows.length
  for (let k = 0; k < other; k++) {
    const r = axis === 'row' ? index : k
    const c = axis === 'row' ? k : index
    if (!mergeAt(l.merges, r, c)) l.blocks.push(newBlock(l, r, c))
  }
  reconcileFits(l)
  return l
}

/** 删掉第 index 行（列）区块；整个在这一行里的区块（和里面的小区）一起删掉，跨行合并的区块变小 */
export function removeTrack(layout: FieldLayout, axis: Axis, index: number): FieldLayout {
  const tracks = axis === 'row' ? layout.rows : layout.cols
  if (tracks.length <= 1 || index < 0 || index >= tracks.length) return layout
  const l = clone(layout)
  ;(axis === 'row' ? l.rows : l.cols).splice(index, 1)
  const start = (s: Span) => (axis === 'row' ? s.r : s.c)
  const len = (s: Span) => (axis === 'row' ? s.rs : s.cs)
  const blocks: LayoutBlock[] = []
  for (const b of l.blocks) {
    const s = spanOf(layout.merges, b.r, b.c)
    if (start(s) === index && len(s) === 1) continue
    if (start(s) > index) {
      if (axis === 'row') b.r--
      else b.c--
    }
    blocks.push(b)
  }
  const merges: Span[] = []
  for (const m of l.merges) {
    const s = { ...m }
    if (start(m) > index) {
      if (axis === 'row') s.r--
      else s.c--
    } else if (start(m) + len(m) > index) {
      if (len(m) === 1) continue
      if (axis === 'row') s.rs--
      else s.cs--
    }
    if (s.rs * s.cs >= 2) merges.push(s)
  }
  l.blocks = blocks
  l.merges = merges
  reconcileFits(l)
  return l
}

/** 区块网格改成 rows 行 cols 列：多了在后面加，少了从后面删 */
export function resizeBlockGrid(layout: FieldLayout, rows: number, cols: number): FieldLayout {
  let l = layout
  while (l.rows.length < rows) l = insertTrack(l, 'row', l.rows.length)
  while (l.rows.length > rows && l.rows.length > 1) l = removeTrack(l, 'row', l.rows.length - 1)
  while (l.cols.length < cols) l = insertTrack(l, 'col', l.cols.length)
  while (l.cols.length > cols && l.cols.length > 1) l = removeTrack(l, 'col', l.cols.length - 1)
  return l
}

// ---------- 合并、拆分区块 ----------

function blockAt(l: FieldLayout, r: number, c: number): LayoutBlock | undefined {
  const m = mergeAt(l.merges, r, c)
  return l.blocks.find((b) => (m ? b.r === m.r && b.c === m.c : b.r === r && b.c === c))
}

/**
 * 区块里小区网格的绝对大小：相对大小换算成实际长度，用来拼接、切分
 */
function absInner(inner: Inner, w: number, h: number): Inner {
  const rs = sum(inner.rows)
  const cs = sum(inner.cols)
  return { ...clone(inner), rows: inner.rows.map((v) => (v / rs) * h), cols: inner.cols.map((v) => (v / cs) * w) }
}

export interface MergeResult {
  layout: FieldLayout
  /** 合并后不再有的小区 id（被并掉的区块里的） */
  dropped: number[]
  /** 小区网格是不是拼起来了（所有小区都保留） */
  joined: boolean
}

/**
 * 合并一片区块。左上角的区块留下（名称、类型），其余并进来。
 * 如果都是普通种植区块，而且同一行的小区行数一样、同一列的小区列数一样，就把小区网格拼起来，所有小区都保留；
 * 否则留下左上角区块的小区网格（拉大），其它区块的小区去掉。
 */
export function mergeBlocks(layout: FieldLayout, a: { r: number; c: number }, b: { r: number; c: number }): MergeResult {
  const rect = expandRect(layout.merges, a, b)
  if (rect.rs * rect.cs < 2) return { layout, dropped: [], joined: true }
  const l = clone(layout)
  const inRect = l.blocks.filter((x) => inside(rect, x.r, x.c))
  const anchor = blockAt(l, rect.r, rect.c)!
  const grid: LayoutBlock[][] = []
  let canJoin = inRect.every((x) => x.kind === 'PLANT' && !x.fit && x.inner && !mergeAt(l.merges, x.r, x.c))
  if (canJoin) {
    for (let i = 0; i < rect.rs; i++) {
      grid.push([])
      for (let j = 0; j < rect.cs; j++) grid[i]!.push(blockAt(l, rect.r + i, rect.c + j)!)
    }
    for (let i = 0; i < rect.rs && canJoin; i++) {
      canJoin = grid[i]!.every((x) => x.inner!.rows.length === grid[i]![0]!.inner!.rows.length)
    }
    for (let j = 0; j < rect.cs && canJoin; j++) {
      canJoin = grid.every((row) => row[j]!.inner!.cols.length === grid[0]![j]!.inner!.cols.length)
    }
  }
  const dropped: number[] = []
  if (canJoin) {
    const rows: number[] = []
    const cols: number[] = []
    const merges: Span[] = []
    const plots: Record<string, number> = {}
    const kinds: Record<string, CellKind> = {}
    let r0 = 0
    for (let i = 0; i < rect.rs; i++) {
      const h = l.rows[rect.r + i]!
      rows.push(...absInner(grid[i]![0]!.inner!, 1, h).rows)
      let c0 = 0
      for (let j = 0; j < rect.cs; j++) {
        const inn = grid[i]![j]!.inner!
        if (i === 0) cols.push(...absInner(grid[0]![j]!.inner!, l.cols[rect.c + j]!, 1).cols)
        for (const m of inn.merges) merges.push({ ...m, r: m.r + r0, c: m.c + c0 })
        for (const [k, id] of Object.entries(inn.plots)) {
          const [r, c] = parseKey(k)
          plots[keyOf(r + r0, c + c0)] = id
        }
        for (const [k, v] of Object.entries(inn.kinds ?? {})) {
          const [r, c] = parseKey(k)
          kinds[keyOf(r + r0, c + c0)] = v
        }
        c0 += inn.cols.length
      }
      r0 += grid[i]![0]!.inner!.rows.length
    }
    anchor.inner = {
      rows: rows.map((v) => round(v, 4)), cols: cols.map((v) => round(v, 4)), merges, plots,
    }
    putKinds(anchor.inner, kinds)
  } else {
    for (const x of inRect) {
      if (x !== anchor) dropped.push(...Object.values(x.inner?.plots ?? {}))
    }
  }
  l.blocks = l.blocks.filter((x) => x === anchor || !inside(rect, x.r, x.c))
  l.merges = l.merges.filter((m) => !containsSpan(rect, m))
  l.merges.push(rect)
  anchor.fit = null
  return { layout: l, dropped, joined: canJoin }
}

/** 把合并的区块拆开。小区网格能按原来的行列边界切开时分给各个区块，否则留在左上角区块里，其它新建 */
export function splitBlock(layout: FieldLayout, blockId: string): FieldLayout {
  const src = layout.blocks.find((b) => b.id === blockId)
  if (!src) return layout
  const span = spanOf(layout.merges, src.r, src.c)
  if (span.rs * span.cs < 2) return layout
  const l = clone(layout)
  const anchor = l.blocks.find((b) => b.id === blockId)!
  l.merges = l.merges.filter((m) => !(m.r === span.r && m.c === span.c))

  const pieces = anchor.kind === 'PLANT' && !anchor.fit && anchor.inner ? cutInner(l, anchor, span) : null
  for (let i = 0; i < span.rs; i++) {
    for (let j = 0; j < span.cs; j++) {
      const r = span.r + i
      const c = span.c + j
      if (i === 0 && j === 0) {
        if (pieces) anchor.inner = pieces[0]![0]!
        continue
      }
      const nb = newBlock(l, r, c, anchor.kind === 'PLANT' ? 'PLANT' : anchor.kind)
      if (pieces) nb.inner = pieces[i]![j]!
      l.blocks.push(nb)
    }
  }
  if (!pieces) anchor.fit = null
  reconcileFits(l)
  return l
}

/** 按区块网格的边界把小区网格切开；切不开（边界不对齐，或合并的小区跨过边界）返回 null */
function cutInner(l: FieldLayout, b: LayoutBlock, span: Span): Inner[][] | null {
  const box = trackRect(l.rows, l.cols, span)
  const abs = absInner(b.inner!, box.w, box.h)
  // 每条区块边界落在第几条小区分界线上；对不上就切不开
  const cut = (sizes: number[], tracks: number[]): number[] | null => {
    const cum = [0]
    for (const v of sizes) cum.push(cum[cum.length - 1]! + v)
    const bounds = [0]
    let acc = 0
    for (const t of tracks) {
      acc += t
      const tol = Math.max(1e-3, acc * 1e-4)
      const pos = cum.findIndex((v) => Math.abs(v - acc) <= tol)
      if (pos <= bounds[bounds.length - 1]!) return null
      bounds.push(pos)
    }
    return bounds[bounds.length - 1] === sizes.length ? bounds : null
  }
  const rb = cut(abs.rows, l.rows.slice(span.r, span.r + span.rs))
  const cb = cut(abs.cols, l.cols.slice(span.c, span.c + span.cs))
  if (!rb || !cb) return null
  for (const m of abs.merges) {
    const ri = rb.findIndex((x, i) => i < rb.length - 1 && m.r >= x && m.r < rb[i + 1]!)
    const ci = cb.findIndex((x, i) => i < cb.length - 1 && m.c >= x && m.c < cb[i + 1]!)
    if (m.r + m.rs > rb[ri + 1]! || m.c + m.cs > cb[ci + 1]!) return null
  }
  const out: Inner[][] = []
  for (let i = 0; i < span.rs; i++) {
    out.push([])
    for (let j = 0; j < span.cs; j++) {
      const r0 = rb[i]!
      const r1 = rb[i + 1]!
      const c0 = cb[j]!
      const c1 = cb[j + 1]!
      const plots: Record<string, number> = {}
      for (const [k, id] of Object.entries(abs.plots)) {
        const [r, c] = parseKey(k)
        if (r >= r0 && r < r1 && c >= c0 && c < c1) plots[keyOf(r - r0, c - c0)] = id
      }
      const kinds: Record<string, CellKind> = {}
      for (const [k, v] of Object.entries(abs.kinds ?? {})) {
        const [r, c] = parseKey(k)
        if (r >= r0 && r < r1 && c >= c0 && c < c1) kinds[keyOf(r - r0, c - c0)] = v
      }
      const piece: Inner = {
        rows: abs.rows.slice(r0, r1).map((v) => round(v, 4)),
        cols: abs.cols.slice(c0, c1).map((v) => round(v, 4)),
        merges: abs.merges.filter((m) => m.r >= r0 && m.r < r1 && m.c >= c0 && m.c < c1)
          .map((m) => ({ ...m, r: m.r - r0, c: m.c - c0 })),
        plots,
      }
      putKinds(piece, kinds)
      out[i]!.push(piece)
    }
  }
  return out
}

// ---------- 区块属性 ----------

function mapBlocks(layout: FieldLayout, ids: string[], fn: (b: LayoutBlock, l: FieldLayout) => void): FieldLayout {
  const l = clone(layout)
  const set = new Set(ids)
  for (const b of l.blocks) if (set.has(b.id)) fn(b, l)
  return l
}

export function renameBlock(layout: FieldLayout, id: string, name: string): FieldLayout {
  return mapBlocks(layout, [id], (b) => { b.name = name.trim().slice(0, 30) || b.name })
}

/** 改区块类型。改成道路、保护区、空地时，里面的小区去掉；改回种植区块时按默认行列分小区 */
export function setBlockKind(layout: FieldLayout, ids: string[], kind: BlockKind): FieldLayout {
  return mapBlocks(layout, ids, (b, l) => {
    if (b.kind === kind) return
    if (kind === 'PLANT') {
      const d = l.defaultInner ?? { rows: 1, cols: 1 }
      b.inner = evenInner(d.rows, d.cols)
      if (b.name === BLOCK_KIND[b.kind]) b.name = `区块${b.id.replace(/\D/g, '')}`
    } else {
      b.inner = null
      if (b.kind === 'PLANT' || b.name === BLOCK_KIND[b.kind]) b.name = BLOCK_KIND[kind]
    }
    b.kind = kind
  })
}

/**
 * 特殊区块：设实际大小（不超过槽位），x、y 是在槽位里的偏移（不给时保持中心）。
 * 和槽位一样大时恢复成普通区块。缩小后槽位里其余部分是道路
 */
export function setBlockFit(layout: FieldLayout, id: string, w: number, h: number, pos?: { x: number; y: number }): FieldLayout {
  return mapBlocks(layout, [id], (b, l) => {
    const s = blockSlot(l, b)
    const nw = clamp(w, Math.min(s.w, 0.01), s.w)
    const nh = clamp(h, Math.min(s.h, 0.01), s.h)
    if (Math.abs(nw - s.w) < EPS && Math.abs(nh - s.h) < EPS) {
      b.fit = null
      return
    }
    const cur = b.fit ?? { x: 0, y: 0, w: s.w, h: s.h }
    const x = pos?.x ?? cur.x + (cur.w - nw) / 2
    const y = pos?.y ?? cur.y + (cur.h - nh) / 2
    b.fit = { w: round(nw, 4), h: round(nh, 4), x: round(clamp(x, 0, s.w - nw), 4), y: round(clamp(y, 0, s.h - nh), 4) }
  })
}

/** 移动特殊区块（在槽位里），dx、dy 是相对现在的位置 */
export function moveBlockFit(layout: FieldLayout, id: string, x: number, y: number): FieldLayout {
  return mapBlocks(layout, [id], (b, l) => {
    if (!b.fit) return
    const s = blockSlot(l, b)
    b.fit = { ...b.fit, x: round(clamp(x, 0, s.w - b.fit.w), 4), y: round(clamp(y, 0, s.h - b.fit.h), 4) }
  })
}

export type Align = 'left' | 'hcenter' | 'right' | 'top' | 'vcenter' | 'bottom'

export function alignBlockFit(layout: FieldLayout, id: string, align: Align): FieldLayout {
  const b = layout.blocks.find((x) => x.id === id)
  if (!b?.fit) return layout
  const s = blockSlot(layout, b)
  const { x, y, w, h } = b.fit
  const pos = {
    left: { x: 0, y }, hcenter: { x: (s.w - w) / 2, y }, right: { x: s.w - w, y },
    top: { x, y: 0 }, vcenter: { x, y: (s.h - h) / 2 }, bottom: { x, y: s.h - h },
  }[align]
  return moveBlockFit(layout, id, pos.x, pos.y)
}

// ---------- 区块里的小区网格 ----------

function mapInner(layout: FieldLayout, id: string, fn: (inner: Inner, b: LayoutBlock, l: FieldLayout) => void): FieldLayout {
  const b = layout.blocks.find((x) => x.id === id)
  if (!b?.inner) return layout
  return mapBlocks(layout, [id], (x, l) => fn(x.inner!, x, l))
}

/** 区块里改成 rows 行 cols 列小区：多了在后面加（大小取平均），少了从后面删（合并跨过去的拆开） */
export function resizeInner(layout: FieldLayout, id: string, rows: number, cols: number): FieldLayout {
  const nr = clamp(Math.round(rows), 1, MAX_INNER_TRACKS)
  const nc = clamp(Math.round(cols), 1, MAX_INNER_TRACKS)
  return mapInner(layout, id, (inner) => {
    const grow = (a: number[], n: number) => {
      const avg = sum(a) / a.length
      while (a.length < n) a.push(round(avg, 4))
      a.length = n
    }
    grow(inner.rows, nr)
    grow(inner.cols, nc)
    inner.merges = inner.merges.filter((m) => m.r + m.rs <= nr && m.c + m.cs <= nc)
    const gone = (k: string) => {
      const [r, c] = parseKey(k)
      return r >= nr || c >= nc || (!!mergeAt(inner.merges, r, c) && !inner.merges.some((m) => m.r === r && m.c === c))
    }
    for (const k of Object.keys(inner.plots)) if (gone(k)) delete inner.plots[k]
    const kinds = { ...(inner.kinds ?? {}) }
    for (const k of Object.keys(kinds)) if (gone(k)) delete kinds[k]
    putKinds(inner, kinds)
  })
}

/**
 * 改区块里一行（列）小区的实际大小，其它行（列）按原来的比例分剩下的，区块总大小不变。
 * size 超出区块大小时不改
 */
export function setInnerTrackSize(layout: FieldLayout, id: string, axis: Axis, index: number, size: number): FieldLayout {
  const b = layout.blocks.find((x) => x.id === id)
  if (!b?.inner) return layout
  const actual = innerSizes(layout, b)
  const sizes = axis === 'row' ? actual.rows : actual.cols
  const total = sum(sizes)
  if (index < 0 || index >= sizes.length || !(size > 0) || (sizes.length > 1 && size >= total - EPS)) return layout
  if (sizes.length === 1) return layout
  const rest = total - sizes[index]!
  const next = sizes.map((v, i) => (i === index ? size : (v / rest) * (total - size)))
  return mapInner(layout, id, (inner) => {
    const target = next.map((v) => round(v, 4))
    if (axis === 'row') inner.rows = target
    else inner.cols = target
  })
}

/** 拖动区块里小区之间的分界线：第 index 行（列）小区改成 a，下一行（列）改成 b（实际大小），其它不变 */
export function setInnerTrackPair(layout: FieldLayout, id: string, axis: Axis, index: number, a: number, b: number): FieldLayout {
  const blk = layout.blocks.find((x) => x.id === id)
  if (!blk?.inner || !(a > 0) || !(b > 0)) return layout
  const actual = innerSizes(layout, blk)
  const sizes = axis === 'row' ? actual.rows : actual.cols
  if (index < 0 || index + 1 >= sizes.length) return layout
  const next = [...sizes]
  next[index] = a
  next[index + 1] = b
  return mapInner(layout, id, (inner) => {
    const v = next.map((x) => round(x, 4))
    if (axis === 'row') inner.rows = v
    else inner.cols = v
  })
}

const byRowCol = (a: string, b: string) => {
  const [ar, ac] = parseKey(a)
  const [br, bc] = parseKey(b)
  return ar - br || ac - bc
}

/** 把格子类型写回去：没有特殊类型时去掉 kinds */
function putKinds(inner: Inner, kinds: Record<string, CellKind>) {
  for (const [k, v] of Object.entries(kinds)) if (v === 'PLANT') delete kinds[k]
  if (Object.keys(kinds).length > 0) inner.kinds = kinds
  else delete inner.kinds
}

/**
 * 能不能把区块里选中的格子整体挪 dr 行 dc 列：只能挪没合并的格子，不能挪出区块，也不能挪到合并的格子上
 */
export function canMoveCells(layout: FieldLayout, id: string, keys: string[], dr: number, dc: number): boolean {
  const blk = layout.blocks.find((x) => x.id === id)
  if (!blk?.inner || keys.length === 0) return false
  const { rows, cols, merges } = blk.inner
  return keys.every((k) => {
    const [r, c] = parseKey(k)
    const tr = r + dr
    const tc = c + dc
    return !mergeAt(merges, r, c) && tr >= 0 && tc >= 0 && tr < rows.length && tc < cols.length && !mergeAt(merges, tr, tc)
  })
}

/**
 * 把区块里选中的格子整体挪 dr 行 dc 列：小区（编号、品种、数据都跟着小区）和格子类型一起挪，
 * 挪过去的地方原来的小区换到空出来的格子里。挪不了时返回原布局
 */
export function moveCells(layout: FieldLayout, id: string, keys: string[], dr: number, dc: number): FieldLayout {
  if ((dr === 0 && dc === 0) || !canMoveCells(layout, id, keys, dr, dc)) return layout
  const src = [...new Set(keys)].sort(byRowCol)
  const tgt = src.map((k) => {
    const [r, c] = parseKey(k)
    return keyOf(r + dr, c + dc)
  })
  const srcSet = new Set(src)
  const tgtSet = new Set(tgt)
  const vacated = src.filter((k) => !tgtSet.has(k)).sort(byRowCol)
  const displaced = tgt.filter((k) => !srcSet.has(k)).sort(byRowCol)
  return mapInner(layout, id, (inner) => {
    const oldPlots = { ...inner.plots }
    const oldKinds = { ...(inner.kinds ?? {}) }
    const plots = { ...inner.plots }
    const kinds = { ...(inner.kinds ?? {}) }
    for (const k of [...src, ...tgt]) {
      delete plots[k]
      delete kinds[k]
    }
    const put = (to: string, from: string) => {
      if (oldPlots[from] != null) plots[to] = oldPlots[from]!
      if (oldKinds[from]) kinds[to] = oldKinds[from]!
    }
    src.forEach((k, i) => put(tgt[i]!, k))
    vacated.forEach((k, i) => put(k, displaced[i]!))
    inner.plots = plots
    putKinds(inner, kinds)
  })
}

/** 交换两个区块的位置（占的行列数一样的才能换）：名称、类型、里面的小区都跟着换 */
export function swapBlocks(layout: FieldLayout, a: string, b: string): FieldLayout {
  const A = layout.blocks.find((x) => x.id === a)
  const B = layout.blocks.find((x) => x.id === b)
  if (!A || !B || A === B) return layout
  const sa = blockSpan(layout, A)
  const sb = blockSpan(layout, B)
  if (sa.rs !== sb.rs || sa.cs !== sb.cs) return layout
  const l = clone(layout)
  const x = l.blocks.find((v) => v.id === a)!
  const y = l.blocks.find((v) => v.id === b)!
  ;[x.r, y.r] = [y.r, x.r]
  ;[x.c, y.c] = [y.c, x.c]
  reconcileFits(l)
  return l
}

/** 区块里各行（列）小区平分 */
export function evenInnerTracks(layout: FieldLayout, id: string, axis: Axis): FieldLayout {
  return mapInner(layout, id, (inner) => {
    if (axis === 'row') inner.rows = inner.rows.map(() => 1)
    else inner.cols = inner.cols.map(() => 1)
  })
}

/**
 * 合并区块里的一片小区。留下左上角的小区（没有就留范围里第一个已有的小区），
 * 其余小区去掉；返回去掉的小区 id
 */
export function mergeInner(
  layout: FieldLayout, id: string, a: { r: number; c: number }, b: { r: number; c: number },
): { layout: FieldLayout; dropped: number[] } {
  const blk = layout.blocks.find((x) => x.id === id)
  if (!blk?.inner) return { layout, dropped: [] }
  const rect = expandRect(blk.inner.merges, a, b)
  if (rect.rs * rect.cs < 2) return { layout, dropped: [] }
  const dropped: number[] = []
  const next = mapInner(layout, id, (inner) => {
    const anchorKey = keyOf(rect.r, rect.c)
    const inRect = Object.entries(inner.plots)
      .map(([k, v]) => ({ k, v, rc: parseKey(k) }))
      .filter((x) => inside(rect, x.rc[0], x.rc[1]))
      .sort((p, q) => p.rc[0] - q.rc[0] || p.rc[1] - q.rc[1])
    const keep = inner.plots[anchorKey] ?? inRect[0]?.v
    for (const x of inRect) {
      delete inner.plots[x.k]
      if (x.v !== keep) dropped.push(x.v)
    }
    if (keep != null) inner.plots[anchorKey] = keep
    // 合并后的格子用左上角格子的类型
    const kinds = { ...(inner.kinds ?? {}) }
    const anchorKind = kinds[anchorKey]
    for (const k of Object.keys(kinds)) {
      const [r, c] = parseKey(k)
      if (inside(rect, r, c)) delete kinds[k]
    }
    if (anchorKind) kinds[anchorKey] = anchorKind
    putKinds(inner, kinds)
    inner.merges = inner.merges.filter((m) => !containsSpan(rect, m))
    inner.merges.push(rect)
  })
  return { layout: next, dropped }
}

/** 拆开区块里合并的小区：左上角留原来的小区，其它格子是新小区（类型和原来一样） */
export function splitInner(layout: FieldLayout, id: string, r: number, c: number): FieldLayout {
  return mapInner(layout, id, (inner) => {
    const m = mergeAt(inner.merges, r, c)
    if (!m) return
    inner.merges = inner.merges.filter((x) => x !== m)
    const kind = inner.kinds?.[keyOf(m.r, m.c)]
    if (kind) {
      const kinds = { ...(inner.kinds ?? {}) }
      for (let i = m.r; i < m.r + m.rs; i++) for (let j = m.c; j < m.c + m.cs; j++) kinds[keyOf(i, j)] = kind
      putKinds(inner, kinds)
    }
  })
}

/** 一个小区的中心点（在整块地里的坐标）和格子类型，重新分小区时按位置找新格子 */
export interface PlotSpot {
  plotId: number
  kind: CellKind | null
  x: number
  y: number
}

/** 这些区块里每个小区的中心点 */
export function plotSpots(l: FieldLayout, blockIds: string[]): PlotSpot[] {
  const out: PlotSpot[] = []
  for (const b of l.blocks) {
    if (!blockIds.includes(b.id) || !b.inner) continue
    for (const c of innerCells(l, b)) {
      if (c.plotId == null) continue
      out.push({ plotId: c.plotId, kind: b.inner.kinds?.[c.key] ?? null, x: c.rect.x + c.rect.w / 2, y: c.rect.y + c.rect.h / 2 })
    }
  }
  return out
}

/**
 * 区块里重新分成 rows 行 cols 列（各行各列一样大）。原来的小区按位置留下：小区中心落在哪个新格子里，
 * 就成为那个格子的小区（编号、品种、数据都跟着）；一个新格子落进几个小区时留左上角的，其余去掉。
 * spots 是原来的小区在哪（合并区块时传合并前各个区块的小区），不传时用这个区块现在的小区
 */
export function regridInner(
  layout: FieldLayout, id: string, rows: number, cols: number, spots?: PlotSpot[],
): { layout: FieldLayout; dropped: number[] } {
  const blk = layout.blocks.find((x) => x.id === id)
  if (!blk?.inner && !spots) return { layout, dropped: [] }
  if (!blk) return { layout, dropped: [] }
  const nr = clamp(Math.round(rows), 1, MAX_INNER_TRACKS)
  const nc = clamp(Math.round(cols), 1, MAX_INNER_TRACKS)
  const box = blockRect(layout, blk)
  const src = [...(spots ?? plotSpots(layout, [id]))].sort((a, b) => a.y - b.y || a.x - b.x)
  const plots: Record<string, number> = {}
  const kinds: Record<string, CellKind> = {}
  const dropped: number[] = []
  for (const s of src) {
    const cx = (s.x - box.x) / box.w
    const cy = (s.y - box.y) / box.h
    if (cx < 0 || cy < 0 || cx >= 1 || cy >= 1) {
      dropped.push(s.plotId)
      continue
    }
    const key = keyOf(Math.min(nr - 1, Math.floor(cy * nr)), Math.min(nc - 1, Math.floor(cx * nc)))
    if (plots[key] == null) {
      plots[key] = s.plotId
      if (s.kind) kinds[key] = s.kind
    } else {
      dropped.push(s.plotId)
    }
  }
  const next = mapBlocks(layout, [id], (b) => {
    const inner: Inner = { rows: Array(nr).fill(1), cols: Array(nc).fill(1), merges: [], plots }
    putKinds(inner, kinds)
    b.inner = inner
  })
  return { layout: next, dropped }
}

/** 给区块里选中的格子设类型：种植小区、道路、保护区、空地（小区还在，保存后小区类型跟着变） */
export function setCellKinds(layout: FieldLayout, id: string, keys: string[], kind: CellKind): FieldLayout {
  const blk = layout.blocks.find((x) => x.id === id)
  if (!blk?.inner || keys.length === 0) return layout
  if (keys.every((k) => cellKind(blk.inner, k) === kind)) return layout
  return mapInner(layout, id, (inner) => {
    const kinds = { ...(inner.kinds ?? {}) }
    for (const k of keys) kinds[k] = kind
    putKinds(inner, kinds)
  })
}

// ---------- 单位 ----------

/** 换单位：convert 为 true 时保持实际大小（数字跟着换算），否则只换单位名（数字不变） */
export function setUnit(layout: FieldLayout, unit: Unit, convert: boolean): FieldLayout {
  const l = clone(layout)
  const k = convert ? layout.unit.meters / unit.meters : 1
  l.unit = { ...unit }
  if (k !== 1) {
    l.rows = l.rows.map((v) => round(v * k, 4))
    l.cols = l.cols.map((v) => round(v * k, 4))
    for (const b of l.blocks) {
      if (b.fit) b.fit = { x: round(b.fit.x * k, 4), y: round(b.fit.y * k, 4), w: round(b.fit.w * k, 4), h: round(b.fit.h * k, 4) }
    }
  }
  return l
}

/** 保存前检查：返回第一个问题，没问题返回 null */
export function checkLayout(l: FieldLayout): string | null {
  if (!(l.unit.meters > 0) || l.unit.meters > 1000) return '长度单位不对'
  if (l.rows.length > MAX_BLOCK_TRACKS || l.cols.length > MAX_BLOCK_TRACKS) return `区块最多 ${MAX_BLOCK_TRACKS} 行、${MAX_BLOCK_TRACKS} 列`
  if (plotCount(l) > MAX_PLOTS) return `小区太多了：一块地最多 ${MAX_PLOTS} 个小区`
  if (l.blocks.some((b) => b.kind === 'PLANT' && b.inner && (b.inner.rows.length > MAX_INNER_TRACKS || b.inner.cols.length > MAX_INNER_TRACKS))) {
    return `一个区块里最多 ${MAX_INNER_TRACKS} 行、${MAX_INNER_TRACKS} 列小区`
  }
  return null
}
