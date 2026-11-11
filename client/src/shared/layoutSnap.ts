// 编辑地块时的吸附：拖区块、拉边、画道路时，边贴到别的区块的边、地块的边上，
// 或者贴到离别的区块正好一个路宽的地方。都是纯函数，画布拿结果去画辅助线。
import { blockSeams, type Axis, type FieldLayout, type Rect } from './fieldLayout'

/** 一条能吸上去的线：竖线记 x，横线记 y；from、to 是它在另一个方向上的范围（画辅助线用） */
export interface SnapLine {
  pos: number
  from: number
  to: number
  /** edge 区块或地块的边；gap 离边一个路宽；seam 区块里小区块之间的线 */
  kind: 'edge' | 'gap' | 'seam'
}

export interface SnapLines {
  /** 竖线（x 方向吸） */
  xs: SnapLine[]
  /** 横线（y 方向吸） */
  ys: SnapLine[]
}

export interface Guide {
  axis: 'x' | 'y'
  pos: number
  from: number
  to: number
}

/**
 * 能吸的线：除了 exclude 以外的区块的四条边、地块的四条边；gap 大于 0 时再加上离每条边 gap 远的线
 * （摆区块时正好留出一条路）；seams 为 true 时再加上种植区块里小区块之间的线（画道路用）
 */
export function snapLines(l: FieldLayout, exclude: string[] = [], gap = 0, seams = false): SnapLines {
  const xs: SnapLine[] = [
    { pos: 0, from: 0, to: l.h, kind: 'edge' }, { pos: l.w, from: 0, to: l.h, kind: 'edge' },
  ]
  const ys: SnapLine[] = [
    { pos: 0, from: 0, to: l.w, kind: 'edge' }, { pos: l.h, from: 0, to: l.w, kind: 'edge' },
  ]
  for (const b of l.blocks) {
    if (exclude.includes(b.id)) continue
    for (const x of [b.x, b.x + b.w]) xs.push({ pos: x, from: b.y, to: b.y + b.h, kind: 'edge' })
    for (const y of [b.y, b.y + b.h]) ys.push({ pos: y, from: b.x, to: b.x + b.w, kind: 'edge' })
    if (gap > 0 && b.kind !== 'ROAD') {
      xs.push({ pos: b.x - gap, from: b.y, to: b.y + b.h, kind: 'gap' }, { pos: b.x + b.w + gap, from: b.y, to: b.y + b.h, kind: 'gap' })
      ys.push({ pos: b.y - gap, from: b.x, to: b.x + b.w, kind: 'gap' }, { pos: b.y + b.h + gap, from: b.x, to: b.x + b.w, kind: 'gap' })
    }
    if (seams && b.kind === 'PLANT') {
      for (const x of blockSeams(l, b, 'col').slice(1, -1)) xs.push({ pos: x, from: b.y, to: b.y + b.h, kind: 'seam' })
      for (const y of blockSeams(l, b, 'row').slice(1, -1)) ys.push({ pos: y, from: b.x, to: b.x + b.w, kind: 'seam' })
    }
  }
  return { xs, ys }
}

const RANK = { edge: 0, seam: 1, gap: 2 }

/** 离 v 最近的线（在 tol 以内）；一样近时边优先 */
export function nearestLine(v: number, lines: SnapLine[], tol: number): SnapLine | null {
  let best: SnapLine | null = null
  let d = Infinity
  for (const s of lines) {
    const dist = Math.abs(s.pos - v)
    if (dist > tol) continue
    if (dist < d - 1e-9 || (best && Math.abs(dist - d) <= 1e-9 && RANK[s.kind] < RANK[best.kind])) {
      best = s
      d = dist
    }
  }
  return best
}

/** 没吸到线时按 step 取整（step 为 0 不取整） */
const tidy = (v: number, step: number) => (step > 0 ? Math.round(v / step) * step : v)

export interface SnapResult {
  dx: number
  dy: number
  guides: Guide[]
}

function guide(axis: 'x' | 'y', line: SnapLine, lo: number, hi: number): Guide {
  return { axis, pos: line.pos, from: Math.min(line.from, lo), to: Math.max(line.to, hi) }
}

/**
 * 挪一个矩形（几个一起挪时是它们的外框）：它的左边或右边贴到竖线上、上边或下边贴到横线上。
 * 返回要补上的 dx、dy（已经挪到 rect 这里了，再补这么多就贴上）和辅助线；没贴上线的方向，左上角按 step 取整
 */
export function snapMove(rect: Rect, lines: SnapLines, tol: number, step = 0): SnapResult {
  const out: SnapResult = { dx: tidy(rect.x, step) - rect.x, dy: tidy(rect.y, step) - rect.y, guides: [] }
  let best: { d: number; line: SnapLine } | null = null
  for (const edge of [rect.x, rect.x + rect.w]) {
    const line = nearestLine(edge, lines.xs, tol)
    if (line && (!best || Math.abs(line.pos - edge) < Math.abs(best.d) - 1e-9)) best = { d: line.pos - edge, line }
  }
  if (best) {
    out.dx = best.d
    out.guides.push(guide('x', best.line, rect.y, rect.y + rect.h))
  }
  best = null
  for (const edge of [rect.y, rect.y + rect.h]) {
    const line = nearestLine(edge, lines.ys, tol)
    if (line && (!best || Math.abs(line.pos - edge) < Math.abs(best.d) - 1e-9)) best = { d: line.pos - edge, line }
  }
  if (best) {
    out.dy = best.d
    out.guides.push(guide('y', best.line, rect.x, rect.x + rect.w))
  }
  return out
}

/** 一个点吸到线上：返回吸好的点和辅助线（画区块的角、道路的两头用） */
export function snapPoint(
  p: { x: number; y: number }, lines: SnapLines, tol: number, step = 0,
): { x: number; y: number; guides: Guide[] } {
  const guides: Guide[] = []
  const lx = nearestLine(p.x, lines.xs, tol)
  const ly = nearestLine(p.y, lines.ys, tol)
  if (lx) guides.push(guide('x', lx, p.y, p.y))
  if (ly) guides.push(guide('y', ly, p.x, p.x))
  return { x: lx ? lx.pos : tidy(p.x, step), y: ly ? ly.pos : tidy(p.y, step), guides }
}

/** 八个拉手：n 上、s 下、w 左、e 右和四个角 */
export type Handle = 'n' | 's' | 'w' | 'e' | 'nw' | 'ne' | 'sw' | 'se'

/**
 * 拉着一个拉手改大小：p 是鼠标现在的位置，start 是开始时的矩形。拉的那条边贴到线上（没贴上按 step 取整），最小 min
 */
export function resizeBy(
  start: Rect, handle: Handle, p: { x: number; y: number }, lines: SnapLines, tol: number, min: number, step = 0,
): { rect: Rect; guides: Guide[] } {
  let x0 = start.x
  let x1 = start.x + start.w
  let y0 = start.y
  let y1 = start.y + start.h
  const guides: Guide[] = []
  const snapX = (v: number) => {
    const line = nearestLine(v, lines.xs, tol)
    if (line) guides.push(guide('x', line, y0, y1))
    return line ? line.pos : tidy(v, step)
  }
  const snapY = (v: number) => {
    const line = nearestLine(v, lines.ys, tol)
    if (line) guides.push(guide('y', line, x0, x1))
    return line ? line.pos : tidy(v, step)
  }
  if (handle.includes('w')) x0 = Math.min(snapX(p.x), x1 - min)
  if (handle.includes('e')) x1 = Math.max(snapX(p.x), x0 + min)
  if (handle.includes('n')) y0 = Math.min(snapY(p.y), y1 - min)
  if (handle.includes('s')) y1 = Math.max(snapY(p.y), y0 + min)
  return { rect: { x: x0, y: y0, w: x1 - x0, h: y1 - y0 }, guides }
}

/**
 * 画道路：从 a 拖到 b。横着拖得多就是横向道路，否则纵向；路中心在 a 这条线上，两头是 a、b。
 * 返回道路的长条和走向（还没有吸附、切开，交给 drawRoad）
 */
export function roadStrip(a: { x: number; y: number }, b: { x: number; y: number }, width: number): { rect: Rect; axis: Axis } {
  if (Math.abs(b.x - a.x) >= Math.abs(b.y - a.y)) {
    return { axis: 'row', rect: { x: Math.min(a.x, b.x), y: a.y - width / 2, w: Math.abs(b.x - a.x), h: width } }
  }
  return { axis: 'col', rect: { x: a.x - width / 2, y: Math.min(a.y, b.y), w: width, h: Math.abs(b.y - a.y) } }
}
