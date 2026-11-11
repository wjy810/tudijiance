// 地块布局：地块 → 区块 → 小区块。和服务器 FieldLayout.java 的字段一致。
//
//   地块      一个矩形（w × h，单位见 unit）。区块、道路都是摆在里面的矩形，位置和大小随便改
//   区块      x、y 是左上角在地块里的位置，w、h 是大小。PLANT 种植区块里再分小区块网格（inner）；
//             ROAD 道路、GUARD 保护区、BLANK 空地没有小区块。没被盖住的地方就是空着的地
//   小区块网格  inner.rows / inner.cols 是相对大小，按区块实际大小分；可以合并；inner.plots 记格子对应的小区块 id
//
// 区块之间不能叠在一起，道路和道路可以交叉。画一条道路穿过区块时，区块在最近的小区块分界线处切开，
// 后面的往后挪一个路宽（小区块大小不变，地块跟着变大）。
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

/** 区块里一格（小区块）的类型：种植小区块、道路（走道）、保护区、空地 */
export type CellKind = 'PLANT' | 'ROAD' | 'GUARD' | 'BLANK'

export interface Inner {
  rows: number[]
  cols: number[]
  merges: Span[]
  /** "行,列" → 小区块 id；新加的格子没有，保存后服务器补上 */
  plots: Record<string, number>
  /** "行,列" → 格子类型；没写的是种植小区块 */
  kinds?: Record<string, CellKind>
}

export interface LayoutBlock {
  id: string
  name: string
  kind: BlockKind
  /** 左上角在地块里的位置和大小（单位同布局） */
  x: number
  y: number
  w: number
  h: number
  inner: Inner | null
}

export interface FieldLayout {
  version: 2
  unit: Unit
  /** 地块的大小 */
  w: number
  h: number
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
  PLANT: '种植小区块',
  ROAD: '道路',
  GUARD: '保护区',
  BLANK: '空地',
}

/** 格子类型，没写的是种植小区块 */
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

export const MAX_BLOCKS = 400
export const MAX_INNER_TRACKS = 60
export const MAX_PLOTS = 3000
/** 地块最大多少（单位同布局） */
export const MAX_FIELD = 100000
/** 区块、道路最小多大 */
export const MIN_SIZE = 0.01
const EPS = 1e-6
/** 两个矩形挨着还是叠着：差这么一点不算叠（位置只记到小数点后 4 位，三等分时会有零头） */
export const TOL = 1e-3

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
const r4 = (v: number) => round(v, 4)
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
  return { w: l.w, h: l.h }
}

/** 网格里一个范围的矩形 */
export function trackRect(rows: number[], cols: number[], s: Span): Rect {
  return { x: sum(cols, 0, s.c), y: sum(rows, 0, s.r), w: sum(cols, s.c, s.c + s.cs), h: sum(rows, s.r, s.r + s.rs) }
}

/** 区块占的地方 */
export function blockRect(_l: FieldLayout, b: LayoutBlock): Rect {
  return { x: b.x, y: b.y, w: b.w, h: b.h }
}

/** 两个矩形是不是叠在一起（只是挨着不算） */
export function overlaps(a: Rect, b: Rect, tol = TOL): boolean {
  return Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x) > tol && Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y) > tol
}

/** 几个矩形的外框 */
export function boundsOf(rects: Rect[]): Rect | null {
  if (rects.length === 0) return null
  const x = Math.min(...rects.map((r) => r.x))
  const y = Math.min(...rects.map((r) => r.y))
  return { x, y, w: Math.max(...rects.map((r) => r.x + r.w)) - x, h: Math.max(...rects.map((r) => r.y + r.h)) - y }
}

/** 叠在一起的区块（两两一对）。道路和道路可以交叉，不算 */
export function conflicts(l: FieldLayout): [string, string][] {
  const out: [string, string][] = []
  for (let i = 0; i < l.blocks.length; i++) {
    for (let j = i + 1; j < l.blocks.length; j++) {
      const a = l.blocks[i]!
      const b = l.blocks[j]!
      if (a.kind === 'ROAD' && b.kind === 'ROAD') continue
      if (overlaps(a, b)) out.push([a.id, b.id])
    }
  }
  return out
}

export interface CellGeom {
  key: string
  span: Span
  rect: Rect
  plotId: number | null
  kind: CellKind
}

/** 区块里的小区块（格子），rect 是在整块地里的位置 */
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

/** 区块里每一行、每一列小区块的实际大小（单位同布局） */
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

/** 布局里引用的所有小区块 id */
export function plotIds(l: FieldLayout): Set<number> {
  const out = new Set<number>()
  for (const b of l.blocks) for (const id of Object.values(b.inner?.plots ?? {})) out.add(id)
  return out
}

/** 改布局后会去掉的小区块 */
export function removedPlots(before: FieldLayout, after: FieldLayout): number[] {
  const keep = plotIds(after)
  return [...plotIds(before)].filter((id) => !keep.has(id))
}

/** 小区块所在的区块和格子 */
export function locatePlot(l: FieldLayout, plotId: number): { block: LayoutBlock; key: string } | null {
  for (const b of l.blocks) {
    for (const [key, id] of Object.entries(b.inner?.plots ?? {})) if (id === plotId) return { block: b, key }
  }
  return null
}

/** 小区块在哪：区块名称和在区块里的第几行第几列（合并的写跨几行几列）；找不到时 null */
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

/** 所有小区块在哪（一次算好，平面图上每个小区块的说明用） */
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

/** 种植区块，从上到下、从左到右（手机端、列表按这个顺序排） */
export function plantBlocks(l: FieldLayout): LayoutBlock[] {
  return l.blocks.filter((b) => b.kind === 'PLANT' && b.inner).sort((a, b) => (Math.abs(a.y - b.y) > TOL ? a.y - b.y : a.x - b.x))
}

/** 画的先后：道路垫在下面，区块画在上面 */
export function drawOrder(l: FieldLayout): LayoutBlock[] {
  return [...l.blocks.filter((b) => b.kind === 'ROAD'), ...l.blocks.filter((b) => b.kind !== 'ROAD')]
}

/** 每个小区块多大：各行各列一样大时才有 */
export function plotSize(l: FieldLayout, b: LayoutBlock): { w: number; h: number } | null {
  if (!b.inner) return null
  const even = (a: number[]) => a.every((v) => Math.abs(v - a[0]!) <= Math.abs(a[0]!) * 1e-6)
  if (!even(b.inner.rows) || !even(b.inner.cols)) return null
  return { w: b.w / b.inner.cols.length, h: b.h / b.inner.rows.length }
}

const median = (a: number[]) => {
  const s = [...a].sort((x, y) => x - y)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2
}

/** 这块地里小区块一般多大（各区块取中间值）；还没有种植区块时 null */
export function typicalPlot(l: FieldLayout): { w: number; h: number } | null {
  const sizes = l.blocks.filter((b) => b.kind === 'PLANT').map((b) => plotSize(l, b)).filter((s): s is { w: number; h: number } => !!s)
  if (sizes.length === 0) return null
  return { w: median(sizes.map((s) => s.w)), h: median(sizes.map((s) => s.h)) }
}

// ---------- 整理 ----------

/** 所有区块、道路占的范围 */
export function contentBounds(l: FieldLayout): Rect | null {
  return boundsOf(l.blocks)
}

/**
 * 改完以后整理一下（直接改传进来的布局）：位置和大小只记 4 位小数；有东西挪到了地块左边、上边外面时，
 * 所有东西一起往右、往下挪回来（地块往那边变大）；右边、下边超出时地块跟着变大
 */
function settle(l: FieldLayout): FieldLayout {
  const b = contentBounds(l)
  const dx = b && b.x < -EPS ? -b.x : 0
  const dy = b && b.y < -EPS ? -b.y : 0
  for (const x of l.blocks) {
    x.x = r4(x.x + dx)
    x.y = r4(x.y + dy)
    x.w = r4(x.w)
    x.h = r4(x.h)
  }
  l.w = r4(Math.max(l.w + dx, ...l.blocks.map((x) => x.x + x.w)))
  l.h = r4(Math.max(l.h + dy, ...l.blocks.map((x) => x.y + x.h)))
  return l
}

/** 改地块大小：不能比里面的东西小 */
export function setFieldSize(layout: FieldLayout, w: number, h: number): FieldLayout {
  const b = contentBounds(layout)
  const nw = r4(clamp(w, b ? b.x + b.w : MIN_SIZE, MAX_FIELD))
  const nh = r4(clamp(h, b ? b.y + b.h : MIN_SIZE, MAX_FIELD))
  if (Math.abs(nw - layout.w) < EPS && Math.abs(nh - layout.h) < EPS) return layout
  return { ...layout, w: nw, h: nh }
}

/** 地块收紧到正好放下里面的东西，四周留 margin */
export function fitField(layout: FieldLayout, margin = 0): FieldLayout {
  const b = contentBounds(layout)
  if (!b) return layout
  const l = clone(layout)
  for (const x of l.blocks) {
    x.x = r4(x.x - b.x + margin)
    x.y = r4(x.y - b.y + margin)
  }
  l.w = r4(b.w + margin * 2)
  l.h = r4(b.h + margin * 2)
  return JSON.stringify(l) === JSON.stringify(layout) ? layout : l
}

// ---------- 新建 ----------

export interface CreateOptions {
  blockRows: number
  blockCols: number
  /** 每个区块的宽和高 */
  blockW: number
  blockH: number
  unit: Unit
  /** 每个区块里几行几列小区块 */
  innerRows: number
  innerCols: number
  /** 区块之间的道路宽，0 表示不留 */
  roadW: number
  /** 区块行之间的横向道路宽，不给时用 roadW；0 表示不留 */
  rowRoadW?: number
  /** 区块列之间的纵向道路宽，不给时用 roadW；0 表示不留 */
  colRoadW?: number
}

function evenInner(rows: number, cols: number): Inner {
  return { rows: Array(rows).fill(1), cols: Array(cols).fill(1), merges: [], plots: {} }
}

/**
 * 初始化：几行几列区块、每块多宽多高、里面几行几列小区块，区块之间可以留横向、纵向道路（每条道路通到头）。
 * 地块大小就是区块加道路加起来，以后再随便改
 */
export function createLayout(o: CreateOptions): FieldLayout {
  const rowRoad = Math.max(0, o.rowRoadW ?? o.roadW)
  const colRoad = Math.max(0, o.colRoadW ?? o.roadW)
  const w = r4(o.blockCols * o.blockW + (o.blockCols - 1) * colRoad)
  const h = r4(o.blockRows * o.blockH + (o.blockRows - 1) * rowRoad)
  const blocks: LayoutBlock[] = []
  let no = 1
  for (let i = 0; i < o.blockRows; i++) {
    for (let j = 0; j < o.blockCols; j++) {
      blocks.push({
        id: `B${no}`, name: `区块${no}`, kind: 'PLANT', x: r4(j * (o.blockW + colRoad)), y: r4(i * (o.blockH + rowRoad)),
        w: o.blockW, h: o.blockH, inner: evenInner(o.innerRows, o.innerCols),
      })
      no++
    }
  }
  let roadNo = 1
  const road = (x: number, y: number, rw: number, rh: number) => {
    blocks.push({ id: `R${roadNo++}`, name: '道路', kind: 'ROAD', x: r4(x), y: r4(y), w: r4(rw), h: r4(rh), inner: null })
  }
  if (rowRoad > 0) for (let i = 1; i < o.blockRows; i++) road(0, i * o.blockH + (i - 1) * rowRoad, w, rowRoad)
  if (colRoad > 0) for (let j = 1; j < o.blockCols; j++) road(j * o.blockW + (j - 1) * colRoad, 0, colRoad, h)
  return {
    version: 2, unit: { ...o.unit }, w, h, blocks, nextBlockNo: Math.max(no, roadNo),
    defaultInner: { rows: o.innerRows, cols: o.innerCols }, northDeg: 0,
  }
}

/** 新种植区块的名称：接着现有的"区块几"往下数（道路也占编号，不能直接用编号） */
function nextPlantName(l: FieldLayout): string {
  let max = 0
  for (const b of l.blocks) {
    const m = /^区块(\d+)$/.exec(b.name)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `区块${max + 1}`
}

function nextId(l: FieldLayout): string {
  let n = l.nextBlockNo++
  while (l.blocks.some((b) => b.id === `B${n}`)) n = l.nextBlockNo++
  return `B${n}`
}

/** 一个 w × h 的新种植区块里分几行几列：按这块地里小区块一般的大小算，还没有种植区块时用"新区块默认" */
export function innerCountFor(l: FieldLayout, w: number, h: number): { rows: number; cols: number } {
  const plot = typicalPlot(l)
  if (plot) {
    return { rows: clamp(Math.round(h / plot.h), 1, MAX_INNER_TRACKS), cols: clamp(Math.round(w / plot.w), 1, MAX_INNER_TRACKS) }
  }
  return l.defaultInner ?? { rows: 1, cols: 1 }
}

function innerFor(l: FieldLayout, w: number, h: number): Inner {
  const n = innerCountFor(l, w, h)
  return evenInner(n.rows, n.cols)
}

function newBlock(l: FieldLayout, rect: Rect, kind: BlockKind): LayoutBlock {
  return {
    id: nextId(l), name: kind === 'PLANT' ? nextPlantName(l) : BLOCK_KIND[kind], kind,
    x: rect.x, y: rect.y, w: rect.w, h: rect.h, inner: kind === 'PLANT' ? innerFor(l, rect.w, rect.h) : null,
  }
}

// ---------- 摆放：加、挪、改大小、删、复制 ----------

function mapBlocks(layout: FieldLayout, ids: string[], fn: (b: LayoutBlock, l: FieldLayout) => void): FieldLayout {
  const l = clone(layout)
  const set = new Set(ids)
  for (const b of l.blocks) if (set.has(b.id)) fn(b, l)
  return l
}

const validRect = (r: Rect) => [r.x, r.y, r.w, r.h].every(Number.isFinite) && r.w >= MIN_SIZE - EPS && r.h >= MIN_SIZE - EPS

/** 在 rect 这里加一个区块（道路、保护区、空地也是） */
export function addBlock(layout: FieldLayout, rect: Rect, kind: BlockKind = 'PLANT'): { layout: FieldLayout; id: string | null } {
  if (!validRect(rect) || layout.blocks.length >= MAX_BLOCKS) return { layout, id: null }
  const l = clone(layout)
  const b = newBlock(l, rect, kind)
  l.blocks.push(b)
  return { layout: settle(l), id: b.id }
}

/** 把选中的区块一起挪 dx、dy */
export function moveBlocks(layout: FieldLayout, ids: string[], dx: number, dy: number): FieldLayout {
  if ((Math.abs(dx) < EPS && Math.abs(dy) < EPS) || !ids.some((id) => layout.blocks.some((b) => b.id === id))) return layout
  return settle(mapBlocks(layout, ids, (b) => {
    b.x += dx
    b.y += dy
  }))
}

/** 改一个区块的位置和大小；里面的小区块按比例跟着变 */
export function setBlockRect(layout: FieldLayout, id: string, rect: Rect): FieldLayout {
  const b = layout.blocks.find((x) => x.id === id)
  if (!b || !validRect(rect)) return layout
  if (Math.abs(b.x - rect.x) < EPS && Math.abs(b.y - rect.y) < EPS && Math.abs(b.w - rect.w) < EPS && Math.abs(b.h - rect.h) < EPS) return layout
  return settle(mapBlocks(layout, [id], (x) => Object.assign(x, { x: rect.x, y: rect.y, w: rect.w, h: rect.h })))
}

/** 删掉区块（里面的小区块一起去掉）；地块大小不变 */
export function removeBlocks(layout: FieldLayout, ids: string[]): FieldLayout {
  const set = new Set(ids)
  if (!layout.blocks.some((b) => set.has(b.id))) return layout
  const l = clone(layout)
  l.blocks = l.blocks.filter((b) => !set.has(b.id))
  return l
}

/** 复制出来的区块：一样大、一样的行列和格子类型，小区块是新的 */
function copyOf(l: FieldLayout, b: LayoutBlock, dx: number, dy: number): LayoutBlock {
  const inner = b.inner ? { ...clone(b.inner), plots: {} } : null
  return { ...clone(b), id: nextId(l), name: b.kind === 'PLANT' ? nextPlantName(l) : b.name, x: b.x + dx, y: b.y + dy, inner }
}

/** 把选中的区块复制一份，放在偏移 dx、dy 的地方；返回新区块的 id */
export function duplicateBlocks(layout: FieldLayout, ids: string[], dx: number, dy: number): { layout: FieldLayout; ids: string[] } {
  const src = layout.blocks.filter((b) => ids.includes(b.id))
  if (src.length === 0 || layout.blocks.length + src.length > MAX_BLOCKS) return { layout, ids: [] }
  const l = clone(layout)
  const made: LayoutBlock[] = []
  for (const b of src) {
    const c = copyOf(l, b, dx, dy)
    l.blocks.push(c)
    made.push(c)
  }
  settle(l)
  return { layout: l, ids: made.map((b) => b.id) }
}

/**
 * 照着一个区块排一片：横着 cols 个、竖着 rows 个（算上它自己），间隔 gapX、gapY。返回新区块的 id
 */
export function arrayBlocks(
  layout: FieldLayout, id: string, cols: number, rows: number, gapX: number, gapY: number,
): { layout: FieldLayout; ids: string[] } {
  const src = layout.blocks.find((b) => b.id === id)
  const nc = Math.max(1, Math.round(cols))
  const nr = Math.max(1, Math.round(rows))
  if (!src || nc * nr < 2 || layout.blocks.length + nc * nr - 1 > MAX_BLOCKS) return { layout, ids: [] }
  const l = clone(layout)
  const made: string[] = []
  for (let i = 0; i < nr; i++) {
    for (let j = 0; j < nc; j++) {
      if (i === 0 && j === 0) continue
      const c = copyOf(l, src, j * (src.w + gapX), i * (src.h + gapY))
      l.blocks.push(c)
      made.push(c.id)
    }
  }
  settle(l)
  return { layout: l, ids: made }
}

export type Align = 'left' | 'hcenter' | 'right' | 'top' | 'vcenter' | 'bottom'

/** 对齐：选了几个时向它们的外框对齐，只选一个时向地块对齐 */
export function alignBlocks(layout: FieldLayout, ids: string[], align: Align): FieldLayout {
  const sel = layout.blocks.filter((b) => ids.includes(b.id))
  if (sel.length === 0) return layout
  const box = sel.length > 1 ? boundsOf(sel)! : { x: 0, y: 0, w: layout.w, h: layout.h }
  const next = mapBlocks(layout, ids, (b) => {
    if (align === 'left') b.x = box.x
    else if (align === 'right') b.x = box.x + box.w - b.w
    else if (align === 'hcenter') b.x = box.x + (box.w - b.w) / 2
    else if (align === 'top') b.y = box.y
    else if (align === 'bottom') b.y = box.y + box.h - b.h
    else b.y = box.y + (box.h - b.h) / 2
  })
  settle(next)
  return JSON.stringify(next) === JSON.stringify(layout) ? layout : next
}

/**
 * 排开：按现在的先后顺序沿横向（col）或纵向（row）排。给了 gap 时从第一个开始一个挨一个、中间隔 gap；
 * 不给时第一个和最后一个不动，中间的间隔匀开
 */
export function distributeBlocks(layout: FieldLayout, ids: string[], axis: Axis, gap?: number): FieldLayout {
  const sel = layout.blocks.filter((b) => ids.includes(b.id))
  if (sel.length < 2) return layout
  const pos = (b: Rect) => (axis === 'col' ? b.x : b.y)
  const len = (b: Rect) => (axis === 'col' ? b.w : b.h)
  const order = [...sel].sort((a, b) => pos(a) - pos(b))
  let g = gap
  if (g == null) {
    if (order.length < 3) return layout
    const last = order[order.length - 1]!
    g = (pos(last) + len(last) - pos(order[0]!) - order.reduce((n, b) => n + len(b), 0)) / (order.length - 1)
  }
  const at = new Map<string, number>()
  let cur = pos(order[0]!)
  for (const b of order) {
    at.set(b.id, cur)
    cur += len(b) + g
  }
  const next = mapBlocks(layout, ids, (b) => {
    if (axis === 'col') b.x = at.get(b.id)!
    else b.y = at.get(b.id)!
  })
  settle(next)
  return JSON.stringify(next) === JSON.stringify(layout) ? layout : next
}

// ---------- 区块属性 ----------

export function renameBlock(layout: FieldLayout, id: string, name: string): FieldLayout {
  return mapBlocks(layout, [id], (b) => { b.name = name.trim().slice(0, 30) || b.name })
}

/** 改区块类型。改成道路、保护区、空地时，里面的小区块去掉；改回种植区块时按小区块一般的大小分 */
export function setBlockKind(layout: FieldLayout, ids: string[], kind: BlockKind): FieldLayout {
  if (!layout.blocks.some((b) => ids.includes(b.id) && b.kind !== kind)) return layout
  return mapBlocks(layout, ids, (b, l) => {
    if (b.kind === kind) return
    if (kind === 'PLANT') {
      // 先算行列再改类型：不把自己算进"一般多大"里
      b.inner = innerFor({ ...l, blocks: l.blocks.filter((x) => x !== b) }, b.w, b.h)
      if (b.name === BLOCK_KIND[b.kind]) b.name = nextPlantName(l)
    } else {
      b.inner = null
      if (b.kind === 'PLANT' || b.name === BLOCK_KIND[b.kind]) b.name = BLOCK_KIND[kind]
    }
    b.kind = kind
  })
}

/**
 * 按每个小区块的大小定区块大小：区块宽 = 列数 × 小区块宽，区块长 = 行数 × 小区块长（各行各列改成一样大）。
 * 左上角不动
 */
export function setPlotSize(layout: FieldLayout, ids: string[], w: number | null, h: number | null): FieldLayout {
  const ok = (v: number | null) => v == null || (Number.isFinite(v) && v >= MIN_SIZE)
  if (!ok(w) || !ok(h) || (w == null && h == null)) return layout
  if (!layout.blocks.some((b) => ids.includes(b.id) && b.inner)) return layout
  return settle(mapBlocks(layout, ids, (b) => {
    if (!b.inner) return
    if (w != null) {
      b.inner.cols = b.inner.cols.map(() => 1)
      b.w = w * b.inner.cols.length
    }
    if (h != null) {
      b.inner.rows = b.inner.rows.map(() => 1)
      b.h = h * b.inner.rows.length
    }
  }))
}

// ---------- 合并、拆开区块 ----------

export type Axis = 'row' | 'col'

/** 从上到下、从左到右 */
const byPlace = (a: Rect, b: Rect) => (Math.abs(a.y - b.y) > TOL ? a.y - b.y : a.x - b.x)

/** 差不多一样的数算一个，从小到大 */
function cluster(values: number[]): number[] {
  const out: number[] = []
  for (const v of [...values].sort((a, b) => a - b)) {
    if (out.length === 0 || v - out[out.length - 1]! > TOL) out.push(v)
  }
  return out
}

/**
 * 区块里小区块网格的绝对大小：相对大小换算成实际长度，用来拼接、切分
 */
function absInner(inner: Inner, w: number, h: number): Inner {
  const rs = sum(inner.rows)
  const cs = sum(inner.cols)
  return { ...clone(inner), rows: inner.rows.map((v) => (v / rs) * h), cols: inner.cols.map((v) => (v / cs) * w) }
}

/**
 * 几个种植区块是不是整整齐齐排成几行几列（中间可以有空当）：同一行的一样高、小区块行数一样，
 * 同一列的一样宽、小区块列数一样。是的话返回排好的二维数组
 */
function gridOf(blocks: LayoutBlock[]): LayoutBlock[][] | null {
  const xs = cluster(blocks.map((b) => b.x))
  const ys = cluster(blocks.map((b) => b.y))
  if (xs.length * ys.length !== blocks.length) return null
  const grid: LayoutBlock[][] = []
  for (const y of ys) {
    const row: LayoutBlock[] = []
    for (const x of xs) {
      const b = blocks.find((v) => Math.abs(v.x - x) <= TOL && Math.abs(v.y - y) <= TOL)
      if (!b?.inner) return null
      row.push(b)
    }
    grid.push(row)
  }
  for (const row of grid) {
    if (!row.every((b) => Math.abs(b.h - row[0]!.h) <= TOL && b.inner!.rows.length === row[0]!.inner!.rows.length)) return null
  }
  for (let j = 0; j < xs.length; j++) {
    const first = grid[0]![j]!
    if (!grid.every((row) => Math.abs(row[j]!.w - first.w) <= TOL && row[j]!.inner!.cols.length === first.inner!.cols.length)) return null
  }
  return grid
}

export interface MergeResult {
  layout: FieldLayout
  /** 合并后留下的区块 */
  anchorId: string | null
  /** 合并后不再有的小区块 id（被并掉的区块里的） */
  dropped: number[]
  /** 小区块网格是不是拼起来了（所有小区块都保留） */
  joined: boolean
  /** 不能合并时的原因 */
  problem: string | null
}

/**
 * 把选中的几个区块合并成一个，占它们的外框（夹在中间的道路、空当一起并进来）。左上角的种植区块留下（名称、类型），其余并进来。
 * 种植区块整整齐齐排成几行几列时，把小区块网格拼起来，所有小区块都保留；
 * 否则留下左上角区块的小区块网格（拉大），其它区块的小区块去掉。
 * 外框里有没选上的东西时不能合并
 */
export function mergeBlocks(layout: FieldLayout, ids: string[]): MergeResult {
  const none = (problem: string): MergeResult => ({ layout, anchorId: null, dropped: [], joined: false, problem })
  const sel = layout.blocks.filter((b) => ids.includes(b.id))
  if (sel.length < 2) return none('选两个或更多区块再合并')
  const box = boundsOf(sel)!
  const blocker = layout.blocks.find((b) => !ids.includes(b.id) && overlaps(b, box))
  if (blocker) return none(`中间隔着“${blocker.name}”，把它一起选上，或者先挪开`)
  const l = clone(layout)
  const order = l.blocks.filter((b) => ids.includes(b.id)).sort(byPlace)
  const plant = order.filter((b) => b.kind === 'PLANT' && b.inner)
  const anchor = plant[0] ?? order[0]!
  const grid = plant.length > 0 ? gridOf(plant) : null
  const dropped: number[] = []
  if (grid) {
    const rows: number[] = []
    const cols: number[] = []
    const merges: Span[] = []
    const plots: Record<string, number> = {}
    const kinds: Record<string, CellKind> = {}
    let r0 = 0
    for (let i = 0; i < grid.length; i++) {
      rows.push(...absInner(grid[i]![0]!.inner!, 1, grid[i]![0]!.h).rows)
      let c0 = 0
      for (let j = 0; j < grid[i]!.length; j++) {
        const inn = grid[i]![j]!.inner!
        if (i === 0) cols.push(...absInner(inn, grid[0]![j]!.w, 1).cols)
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
    anchor.inner = { rows: rows.map(r4), cols: cols.map(r4), merges, plots }
    putKinds(anchor.inner, kinds)
  } else {
    for (const x of order) {
      if (x !== anchor) dropped.push(...Object.values(x.inner?.plots ?? {}))
    }
  }
  l.blocks = l.blocks.filter((x) => x === anchor || !ids.includes(x.id))
  Object.assign(anchor, { x: box.x, y: box.y, w: box.w, h: box.h })
  return { layout: settle(l), anchorId: anchor.id, dropped, joined: !!grid, problem: null }
}

/** 小区块网格的第 k 条分界线（1..n-1，axis 为 row 是行之间的横线）能不能切：没有合并的小区块跨过去 */
function cuttable(inner: Inner, axis: Axis, k: number): boolean {
  return !inner.merges.some((m) => (axis === 'row' ? m.r < k && m.r + m.rs > k : m.c < k && m.c + m.cs > k))
}

/** 小区块网格里的一片：第 r0..r1 行、c0..c1 列（不含 r1、c1） */
function sliceInner(inner: Inner, r0: number, r1: number, c0: number, c1: number): Inner {
  const within = (r: number, c: number) => r >= r0 && r < r1 && c >= c0 && c < c1
  const plots: Record<string, number> = {}
  for (const [k, id] of Object.entries(inner.plots)) {
    const [r, c] = parseKey(k)
    if (within(r, c)) plots[keyOf(r - r0, c - c0)] = id
  }
  const kinds: Record<string, CellKind> = {}
  for (const [k, v] of Object.entries(inner.kinds ?? {})) {
    const [r, c] = parseKey(k)
    if (within(r, c)) kinds[keyOf(r - r0, c - c0)] = v
  }
  const piece: Inner = {
    rows: inner.rows.slice(r0, r1), cols: inner.cols.slice(c0, c1),
    merges: inner.merges.filter((m) => within(m.r, m.c)).map((m) => ({ ...m, r: m.r - r0, c: m.c - c0 })),
    plots,
  }
  putKinds(piece, kinds)
  return piece
}

/**
 * 按小区块的分界线把种植区块切成几块（直接改 l）：rowCuts、colCuts 是分界线序号，从小到大。
 * 左上角那块留原来的 id 和名称，其它是新区块。返回切出来的各块，一行一行排
 */
function sliceBlock(l: FieldLayout, b: LayoutBlock, rowCuts: number[], colCuts: number[]): LayoutBlock[] {
  const inner = b.inner!
  const sizes = innerSizes(l, b)
  const rb = [0, ...rowCuts, inner.rows.length]
  const cb = [0, ...colCuts, inner.cols.length]
  const box = blockRect(l, b)
  const out: LayoutBlock[] = []
  const parts: { rect: Rect; inner: Inner }[] = []
  for (let i = 0; i < rb.length - 1; i++) {
    for (let j = 0; j < cb.length - 1; j++) {
      parts.push({
        inner: sliceInner(inner, rb[i]!, rb[i + 1]!, cb[j]!, cb[j + 1]!),
        rect: {
          x: box.x + sum(sizes.cols, 0, cb[j]!), y: box.y + sum(sizes.rows, 0, rb[i]!),
          w: sum(sizes.cols, cb[j]!, cb[j + 1]!), h: sum(sizes.rows, rb[i]!, rb[i + 1]!),
        },
      })
    }
  }
  parts.forEach((p, n) => {
    if (n === 0) {
      Object.assign(b, p.rect, { inner: p.inner })
      out.push(b)
      return
    }
    const nb: LayoutBlock = { id: nextId(l), name: nextPlantName(l), kind: 'PLANT', ...p.rect, inner: p.inner }
    l.blocks.push(nb)
    out.push(nb)
  })
  return out
}

export interface SplitResult {
  layout: FieldLayout
  /** 拆出来的各块（第一个是原来的） */
  ids: string[]
  problem: string | null
}

/**
 * 把一个区块拆成 rows 行 × cols 列个区块，拼起来还是原来那么大（拆完可以再拖开、在中间画道路）。
 * 种植区块按小区块的分界线拆，尽量拆得一样多，小区块都保留
 */
export function splitBlock(layout: FieldLayout, id: string, rows: number, cols: number): SplitResult {
  const none = (problem: string): SplitResult => ({ layout, ids: [], problem })
  const src = layout.blocks.find((b) => b.id === id)
  const nr = Math.round(rows)
  const nc = Math.round(cols)
  if (!src) return none('没有这个区块')
  if (!(nr >= 1) || !(nc >= 1) || nr * nc < 2) return none('至少拆成两块')
  if (layout.blocks.length + nr * nc - 1 > MAX_BLOCKS) return none(`区块、道路加起来最多 ${MAX_BLOCKS} 个`)
  const l = clone(layout)
  const b = l.blocks.find((x) => x.id === id)!
  if (b.kind !== 'PLANT' || !b.inner) {
    const made: string[] = [b.id]
    const box = blockRect(l, b)
    const w = box.w / nc
    const h = box.h / nr
    for (let i = 0; i < nr; i++) {
      for (let j = 0; j < nc; j++) {
        const rect = { x: box.x + j * w, y: box.y + i * h, w, h }
        if (i === 0 && j === 0) {
          Object.assign(b, rect)
          continue
        }
        const nb: LayoutBlock = { id: nextId(l), name: b.name, kind: b.kind, ...rect, inner: null }
        l.blocks.push(nb)
        made.push(nb.id)
      }
    }
    return { layout: settle(l), ids: made, problem: null }
  }
  const inner = b.inner
  if (nr > inner.rows.length) return none(`里面只有 ${inner.rows.length} 行小区块，最多拆成 ${inner.rows.length} 行`)
  if (nc > inner.cols.length) return none(`里面只有 ${inner.cols.length} 列小区块，最多拆成 ${inner.cols.length} 列`)
  const cuts = (n: number, parts: number) => Array.from({ length: parts - 1 }, (_, k) => Math.round(((k + 1) * n) / parts))
  const rowCuts = cuts(inner.rows.length, nr)
  const colCuts = cuts(inner.cols.length, nc)
  if (!rowCuts.every((k) => cuttable(inner, 'row', k)) || !colCuts.every((k) => cuttable(inner, 'col', k))) {
    return none('有合并的小区块跨在要切开的地方，先进区块把它拆开')
  }
  const pieces = sliceBlock(l, b, rowCuts, colCuts)
  return { layout: settle(l), ids: pieces.map((p) => p.id), problem: null }
}

// ---------- 区块里的小区块网格 ----------

function mapInner(layout: FieldLayout, id: string, fn: (inner: Inner, b: LayoutBlock, l: FieldLayout) => void): FieldLayout {
  const b = layout.blocks.find((x) => x.id === id)
  if (!b?.inner) return layout
  return mapBlocks(layout, [id], (x, l) => fn(x.inner!, x, l))
}

/** 区块里改成 rows 行 cols 列小区块：多了在后面加（大小取平均），少了从后面删（合并跨过去的拆开） */
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
 * 改区块里一行（列）小区块的实际大小，其它行（列）按原来的比例分剩下的，区块总大小不变。
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

/** 拖动区块里小区块之间的分界线：第 index 行（列）小区块改成 a，下一行（列）改成 b（实际大小），其它不变 */
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
 * 把区块里选中的格子整体挪 dr 行 dc 列：小区块（编号、品种、数据都跟着小区块）和格子类型一起挪，
 * 挪过去的地方原来的小区块换到空出来的格子里。挪不了时返回原布局
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

/** 区块里各行（列）小区块平分 */
export function evenInnerTracks(layout: FieldLayout, id: string, axis: Axis): FieldLayout {
  return mapInner(layout, id, (inner) => {
    if (axis === 'row') inner.rows = inner.rows.map(() => 1)
    else inner.cols = inner.cols.map(() => 1)
  })
}

/**
 * 合并区块里的一片小区块。留下左上角的小区块（没有就留范围里第一个已有的小区块），
 * 其余小区块去掉；返回去掉的小区块 id
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

/** 拆开区块里合并的小区块：左上角留原来的小区块，其它格子是新小区块（类型和原来一样） */
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

/** 一个小区块的中心点（在整块地里的坐标）和格子类型，重新分小区块时按位置找新格子 */
export interface PlotSpot {
  plotId: number
  kind: CellKind | null
  x: number
  y: number
}

/** 这些区块里每个小区块的中心点 */
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
 * 区块里重新分成 rows 行 cols 列（各行各列一样大）。原来的小区块按位置留下：小区块中心落在哪个新格子里，
 * 就成为那个格子的小区块（编号、品种、数据都跟着）；一个新格子落进几个小区块时留左上角的，其余去掉。
 * spots 是原来的小区块在哪（合并区块时传合并前各个区块的小区块），不传时用这个区块现在的小区块
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

/** 给区块里选中的格子设类型：种植小区块、道路、保护区、空地（小区块还在，保存后小区块类型跟着变） */
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

// ---------- 道路 ----------
//
// 道路就是一个长条的矩形，可以随便画、挪、改长短和宽窄。
// 画路时压到了区块：区块在离路最近的小区块分界线处切开，路放在分界线后面，后面的东西往后挪一个路宽
// （小区块大小不变，地块跟着变大）；跨过去的垂直道路跟着变长。

/**
 * 换一个方向看矩形：p、s 是垂直于道路方向的位置和大小（横向道路就是 y 和高），q、t 是顺着道路方向的
 */
interface Bar {
  p: number
  s: number
  q: number
  t: number
}

const toBar = (r: Rect, axis: Axis): Bar => (axis === 'row' ? { p: r.y, s: r.h, q: r.x, t: r.w } : { p: r.x, s: r.w, q: r.y, t: r.h })
const putBar = (r: Rect, axis: Axis, b: Bar) => Object.assign(r, axis === 'row' ? { x: b.q, y: b.p, w: b.t, h: b.s } : { x: b.p, y: b.q, w: b.s, h: b.t })
const barRect = (b: Bar, axis: Axis): Rect => putBar({ x: 0, y: 0, w: 0, h: 0 }, axis, b)

/** 道路的走向：横向（row，左右方向长）还是纵向（col） */
export function roadAxis(b: Rect): Axis {
  return b.w >= b.h ? 'row' : 'col'
}

/** 新加道路默认多宽：已有道路的宽（取中间值），没有时 1 米 */
export function defaultRoadWidth(l: FieldLayout): number {
  const ws = l.blocks.filter((b) => b.kind === 'ROAD').map((b) => Math.min(b.w, b.h))
  return round(ws.length ? median(ws) : 1 / l.unit.meters, 3)
}

/**
 * 区块上能放道路的线（axis 为 row 是横线，返回 y；col 是竖线，返回 x）：两条边，加上里面没被合并的小区块跨过的分界线
 */
export function blockSeams(l: FieldLayout, b: LayoutBlock, axis: Axis): number[] {
  const bar = toBar(b, axis)
  const out = [bar.p]
  if (b.kind === 'PLANT' && b.inner) {
    const sizes = axis === 'row' ? innerSizes(l, b).rows : innerSizes(l, b).cols
    let acc = bar.p
    for (let k = 1; k < sizes.length; k++) {
      acc += sizes[k - 1]!
      if (cuttable(b.inner, axis, k)) out.push(acc)
    }
  }
  out.push(bar.p + bar.s)
  return out
}

/**
 * 往后挪（直接改 l）：start 里的东西各挪多少，grow 里的道路各变长多少；挪了、变长了以后压到的东西跟着被推到后面去
 * （道路和道路可以交叉，不推）。obstacles 是不动的障碍（刚画的道路）。返回挪了几个
 */
function pushAfter(l: FieldLayout, axis: Axis, start: Map<string, number>, grow: Map<string, number>, obstacles: Bar[]): number {
  interface Done {
    bar: Bar
    road: boolean
    from: number
  }
  const done: Done[] = obstacles.map((bar) => ({ bar, road: true, from: bar.p }))
  const items = [...l.blocks].sort((a, b) => toBar(a, axis).p - toBar(b, axis).p)
  let moved = 0
  for (const x of items) {
    const bar = toBar(x, axis)
    const extra = grow.get(x.id) ?? 0
    if (extra > 0) {
      bar.s += extra
      putBar(x, axis, bar)
      done.push({ bar, road: true, from: bar.p })
      continue
    }
    let need = start.get(x.id) ?? 0
    for (const y of done) {
      if (x.kind === 'ROAD' && y.road) continue
      if (y.from > bar.p + TOL) continue
      if (Math.min(bar.q + bar.t, y.bar.q + y.bar.t) - Math.max(bar.q, y.bar.q) <= TOL) continue
      const over = y.bar.p + y.bar.s - (bar.p + need)
      if (over > TOL) need += over
    }
    if (need > EPS) {
      const from = bar.p
      bar.p += need
      putBar(x, axis, bar)
      done.push({ bar, road: x.kind === 'ROAD', from })
      moved++
    }
  }
  return moved
}

/**
 * 地块在 axis 的垂直方向上变大以后（直接改 l）：原来通到地块那一头的垂直道路跟着通到新的边上——
 * 只要延长的那一段不压到区块。before 是原来地块在这个方向上多大，skip 是已经挪过、变长过的
 */
function reachEdge(l: FieldLayout, axis: Axis, before: number, skip: Set<string>) {
  const end = Math.max(before, ...l.blocks.map((x) => toBar(x, axis).p + toBar(x, axis).s))
  if (end - before <= TOL) return
  for (const x of l.blocks) {
    if (x.kind !== 'ROAD' || skip.has(x.id) || roadAxis(x) === axis) continue
    const b = toBar(x, axis)
    if (Math.abs(b.p + b.s - before) > TOL) continue
    const more = barRect({ p: b.p + b.s, s: end - b.p - b.s, q: b.q, t: b.t }, axis)
    if (l.blocks.some((o) => o.kind !== 'ROAD' && overlaps(o, more))) continue
    putBar(x, axis, { ...b, s: end - b.p })
  }
}

export interface RoadResult {
  layout: FieldLayout
  /** 新道路的 id；没画成时 null */
  id: string | null
  /** 切开了几个区块 */
  cut: number
  /** 往后挪了几个 */
  moved: number
  /** 地块变大了多少（道路宽度方向） */
  grew: number
  problem: string | null
}

/**
 * 画一条道路。rect 是画出来的长条，axis 是走向（row 横向、col 纵向）。
 * - 路的两头落在区块里面时：盖住一半以上就延长到区块那头，否则缩回到区块边上；
 * - 没压到区块：就放在画的地方；
 * - 压到了区块：在离路中心最近的分界线（区块的边，或里面小区块之间的线）处放路。
 *   线在区块里面（小区块之间）：区块在这里切开，前面的不动，后面的往后挪一个路宽，跨过去的垂直道路变长；
 *   线在区块的边上：旁边有空当就先占空当，空当不够宽才把后面的往后顶，差多少顶多少。
 *   别的区块都不变小，地块跟着变大。
 */
export function drawRoad(layout: FieldLayout, rect: Rect, axis: Axis): RoadResult {
  const none = (problem: string): RoadResult => ({ layout, id: null, cut: 0, moved: 0, grew: 0, problem })
  if (layout.blocks.length >= MAX_BLOCKS) return none(`区块、道路加起来最多 ${MAX_BLOCKS} 个`)
  const road = toBar(rect, axis)
  const width = road.s
  if (!(width >= MIN_SIZE)) return none('道路太窄了')
  let s0 = road.q
  let s1 = road.q + road.t
  const solid = layout.blocks.filter((b) => b.kind !== 'ROAD')
  const hitsBand = (b: Bar) => Math.min(b.p + b.s, road.p + width) - Math.max(b.p, road.p) > TOL

  // 两头落在区块里面：延长到头，或者缩回到区块边上
  for (let guard = 0; guard < solid.length + 2; guard++) {
    let changed = false
    for (const x of solid) {
      const b = toBar(x, axis)
      if (!hitsBand(b)) continue
      const over = Math.min(b.q + b.t, s1) - Math.max(b.q, s0)
      if (over <= TOL) continue
      const before = b.q < s0 - TOL
      const after = b.q + b.t > s1 + TOL
      if (!before && !after) continue
      if ((before && after) || over >= b.t / 2) {
        s0 = Math.min(s0, b.q)
        s1 = Math.max(s1, b.q + b.t)
      } else if (before) {
        s0 = b.q + b.t
      } else {
        s1 = b.q
      }
      changed = true
    }
    if (!changed) break
  }
  if (s1 - s0 < MIN_SIZE) return none('道路太短了，再拖长一点')

  const inSpan = (b: Bar) => b.q >= s0 - TOL && b.q + b.t <= s1 + TOL
  const crossed = solid.filter((x) => {
    const b = toBar(x, axis)
    return inSpan(b) && hitsBand(b)
  })
  const l = clone(layout)
  const before = toBar({ x: 0, y: 0, w: l.w, h: l.h }, axis).s
  const add = (bar: Bar): string => {
    const nb = newBlock(l, barRect(bar, axis), 'ROAD')
    l.blocks.push(nb)
    return nb.id
  }
  if (crossed.length === 0) {
    const id = add({ p: road.p, s: width, q: s0, t: s1 - s0 })
    settle(l)
    return { layout: l, id, cut: 0, moved: 0, grew: r4(toBar({ x: 0, y: 0, w: l.w, h: l.h }, axis).s - before), problem: null }
  }

  // 放在哪条线上：压到的区块里离路中心最近的分界线
  const center = road.p + width / 2
  let line = 0
  let best = Infinity
  for (const x of crossed) {
    for (const s of blockSeams(layout, x, axis)) {
      if (Math.abs(s - center) < best - EPS) {
        best = Math.abs(s - center)
        line = s
      }
    }
  }

  // 这条线前后有没有空当：没有区块跨在线上时，路先占空当，空当不够宽才把后面的往后顶
  const zone = l.blocks.map((x) => ({ x, b: toBar(x, axis) })).filter((v) => inSpan(v.b))
  const straddles = zone.some((v) => v.x.kind !== 'ROAD' && v.b.p < line - TOL && v.b.p + v.b.s > line + TOL)
  let at = line
  let base = width
  if (!straddles) {
    let after = Infinity
    let ahead = Infinity
    for (const v of zone) {
      if (v.x.kind === 'ROAD') continue
      if (v.b.p >= line - TOL) after = Math.min(after, Math.max(0, v.b.p - line))
      else ahead = Math.min(ahead, Math.max(0, line - v.b.p - v.b.s))
    }
    if (after + ahead >= width - TOL) {
      at = Math.max(line - ahead, Math.min(line, line + after - width))
      base = 0
    } else {
      at = line - ahead
      base = width - after - ahead
    }
  }

  const start = new Map<string, number>()
  const grow = new Map<string, number>()
  let cut = 0
  for (const { x, b } of zone) {
    if (b.p >= line - TOL) {
      if (base > TOL) start.set(x.id, base)
      continue
    }
    if (b.p + b.s <= line + TOL) continue
    // 跨在线上
    if (x.kind === 'ROAD') {
      if (base > TOL) grow.set(x.id, base)
      continue
    }
    const seams = blockSeams(l, x, axis)
    let k = 0
    for (let i = 0; i < seams.length; i++) if (seams[i]! <= line + TOL) k = i
    if (k === 0) {
      // 这个区块里没有能切的线：整个挪到路后面
      start.set(x.id, line + width - b.p)
      continue
    }
    // 第 k 条线是小区块网格的第几条分界线
    const sizes = axis === 'row' ? innerSizes(l, x).rows : innerSizes(l, x).cols
    let index = 0
    let acc = b.p
    for (let i = 1; i < sizes.length; i++) {
      acc += sizes[i - 1]!
      if (Math.abs(acc - seams[k]!) <= EPS) index = i
    }
    const pieces = axis === 'row' ? sliceBlock(l, x, [index], []) : sliceBlock(l, x, [], [index])
    start.set(pieces[1]!.id, line + width - seams[k]!)
    cut++
  }
  const bar: Bar = { p: at, s: width, q: s0, t: s1 - s0 }
  const moved = pushAfter(l, axis, start, grow, [bar])
  reachEdge(l, axis, before, new Set([...start.keys(), ...grow.keys()]))
  const id = add(bar)
  settle(l)
  return { layout: l, id, cut, moved, grew: r4(toBar({ x: 0, y: 0, w: l.w, h: l.h }, axis).s - before), problem: null }
}

/**
 * 把一条线后面（只在 from..to 这一段里）的东西往前收 amount（直接改 l），跨过去的垂直道路变短。
 * 收了以后有东西叠在一起就不收，返回 false
 */
function pullBack(l: FieldLayout, axis: Axis, line: number, from: number, to: number, amount: number): boolean {
  const had = new Set(conflicts(l).map((p) => p.join('|')))
  const backup = clone(l.blocks)
  for (const x of l.blocks) {
    const b = toBar(x, axis)
    if (b.q < from - TOL || b.q + b.t > to + TOL) continue
    if (b.p >= line - TOL) {
      b.p -= amount
      putBar(x, axis, b)
    } else if (x.kind === 'ROAD' && b.p + b.s > line + TOL && b.s - amount >= MIN_SIZE) {
      b.s -= amount
      putBar(x, axis, b)
    }
  }
  if (l.blocks.some((x) => toBar(x, axis).p < -TOL) || conflicts(l).some((p) => !had.has(p.join('|')))) {
    l.blocks = backup
    return false
  }
  // 这一段通到地块两头时，地块跟着变小
  const size = toBar({ x: 0, y: 0, w: l.w, h: l.h }, axis)
  if (from <= TOL && to >= size.t - TOL) {
    const end = Math.max(0, ...l.blocks.map((x) => toBar(x, axis).p + toBar(x, axis).s))
    size.s = Math.max(end, size.s - amount)
    if (axis === 'row') l.h = size.s
    else l.w = size.s
  }
  return true
}

/**
 * 删掉一条道路。close 为 true 时把它后面的东西收回来合拢（这条路通到地块两头时地块跟着变小）；
 * 合拢会让别的东西叠在一起时只删路、不合拢。closed 说明合拢了没有
 */
export function removeRoad(layout: FieldLayout, id: string, close: boolean): { layout: FieldLayout; closed: boolean } {
  const road = layout.blocks.find((b) => b.id === id)
  if (!road) return { layout, closed: false }
  const l = clone(layout)
  l.blocks = l.blocks.filter((b) => b.id !== id)
  if (!close || road.kind !== 'ROAD') return { layout: l, closed: false }
  const axis = roadAxis(road)
  const b = toBar(road, axis)
  const closed = pullBack(l, axis, b.p + b.s, b.q, b.q + b.t, b.s)
  return { layout: settle(l), closed }
}

/**
 * 改道路的宽。变宽时后面的东西往后挪（地块跟着变大），变窄时往前收回来；两边的区块大小不变。
 * 这条路只是摆在空地上、后面没有挨着东西时，也是只改它自己
 */
export function setRoadWidth(layout: FieldLayout, id: string, width: number): FieldLayout {
  const road = layout.blocks.find((b) => b.id === id)
  if (!road || !(width >= MIN_SIZE)) return layout
  const axis = roadAxis(road)
  const old = toBar(road, axis)
  const d = width - old.s
  if (Math.abs(d) < EPS) return layout
  const l = clone(layout)
  const self = l.blocks.find((b) => b.id === id)!
  const line = old.p + old.s
  if (d > 0) {
    const start = new Map<string, number>()
    const grow = new Map<string, number>()
    for (const x of l.blocks) {
      if (x === self) continue
      const b = toBar(x, axis)
      if (b.q < old.q - TOL || b.q + b.t > old.q + old.t + TOL) continue
      if (b.p >= line - TOL) start.set(x.id, d)
      else if (x.kind === 'ROAD' && b.p + b.s > line + TOL) grow.set(x.id, d)
    }
    const bar = { ...old, s: width }
    putBar(self, axis, bar)
    // 自己先拿出来，免得被当成要推的东西
    const at = l.blocks.indexOf(self)
    l.blocks.splice(at, 1)
    pushAfter(l, axis, start, grow, [bar])
    reachEdge(l, axis, toBar({ x: 0, y: 0, w: l.w, h: l.h }, axis).s, new Set([...start.keys(), ...grow.keys()]))
    l.blocks.splice(at, 0, self)
  } else {
    putBar(self, axis, { ...old, s: width })
    pullBack(l, axis, line, old.q, old.q + old.t, -d)
  }
  return settle(l)
}

// ---------- 单位 ----------

/** 换单位：convert 为 true 时保持实际大小（数字跟着换算），否则只换单位名（数字不变） */
export function setUnit(layout: FieldLayout, unit: Unit, convert: boolean): FieldLayout {
  const l = clone(layout)
  const k = convert ? layout.unit.meters / unit.meters : 1
  l.unit = { ...unit }
  if (k !== 1) {
    l.w = r4(l.w * k)
    l.h = r4(l.h * k)
    for (const b of l.blocks) Object.assign(b, { x: r4(b.x * k), y: r4(b.y * k), w: r4(b.w * k), h: r4(b.h * k) })
  }
  return l
}

/** 保存前检查：返回第一个问题，没问题返回 null */
export function checkLayout(l: FieldLayout): string | null {
  if (!(l.unit.meters > 0) || l.unit.meters > 1000) return '长度单位不对'
  if (!(l.w > 0) || !(l.h > 0) || l.w > MAX_FIELD || l.h > MAX_FIELD) return '地块大小不对'
  if (l.blocks.length > MAX_BLOCKS) return `区块、道路加起来最多 ${MAX_BLOCKS} 个`
  if (plotCount(l) > MAX_PLOTS) return `小区块太多了：一块地最多 ${MAX_PLOTS} 个小区块`
  if (l.blocks.some((b) => b.kind === 'PLANT' && b.inner && (b.inner.rows.length > MAX_INNER_TRACKS || b.inner.cols.length > MAX_INNER_TRACKS))) {
    return `一个区块里最多 ${MAX_INNER_TRACKS} 行、${MAX_INNER_TRACKS} 列小区块`
  }
  const tiny = l.blocks.find((b) => !(b.w >= MIN_SIZE) || !(b.h >= MIN_SIZE))
  if (tiny) return `“${tiny.name}”太小了`
  const out = l.blocks.find((b) => b.x < -TOL || b.y < -TOL || b.x + b.w > l.w + TOL || b.y + b.h > l.h + TOL)
  if (out) return `“${out.name}”超出了地块`
  const hit = conflicts(l)[0]
  if (hit) {
    const name = (id: string) => l.blocks.find((b) => b.id === id)?.name ?? id
    return `“${name(hit[0])}”和“${name(hit[1])}”叠在一起了，挪开或者改小一个再保存`
  }
  return null
}
