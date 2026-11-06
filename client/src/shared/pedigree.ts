// 系谱图的排布：按季分列（季按开始日期从左到右），每个品系只出现一次，放在它被选出或杂交出来的那一季。
// 原始材料放在第一次种的那一季；它的子代在更早的季里时（没种就拿来杂交了）放到子代那一季，保证父母总在子代左边；
// 还没种、也没有子代的原始材料放在最左边"还没种"一列。
// 同一季里又有父子关系的（例如这一季种的周麦16、这一季从它选出的周麦16-1），在这一季里再分小列，父母在左。
import type { LineRow, Season } from '@/shared/api'

/** 编号按"数字段按数值"排：25-002 在 25-010 前面，25-001-2 在 25-001-10 前面 */
export function naturalCompare(a: string, b: string): number {
  const pa = a.split(/(\d+)/)
  const pb = b.split(/(\d+)/)
  for (let i = 0; i < Math.min(pa.length, pb.length); i++) {
    const x = pa[i]!
    const y = pb[i]!
    if (x === y) continue
    const nx = /^\d+$/.test(x)
    const ny = /^\d+$/.test(y)
    if (nx && ny) {
      const d = Number(x) - Number(y)
      if (d !== 0) return d
      continue
    }
    return x < y ? -1 : 1
  }
  return pa.length - pb.length
}

export const byCode = (a: LineRow, b: LineRow) => naturalCompare(a.code, b.code)

/** 品系的父母（杂交的母本、父本，选株的来源） */
export function parentIds(l: LineRow): number[] {
  return [l.motherId, l.fatherId, l.parentId].filter((x): x is number => x != null)
}

export function childrenMap(lines: LineRow[]): Map<number, number[]> {
  const map = new Map<number, number[]>()
  for (const l of lines) {
    for (const p of parentIds(l)) {
      const list = map.get(p) ?? []
      list.push(l.id)
      map.set(p, list)
    }
  }
  return map
}

/** 所有祖先（不含自己） */
export function ancestors(id: number, byId: Map<number, LineRow>): Set<number> {
  const out = new Set<number>()
  const stack = [...parentIds(byId.get(id) ?? ({} as LineRow))]
  while (stack.length) {
    const p = stack.pop()!
    if (out.has(p) || p === id) continue
    out.add(p)
    const row = byId.get(p)
    if (row) stack.push(...parentIds(row))
  }
  return out
}

/** 所有后代（不含自己） */
export function descendants(id: number, children: Map<number, number[]>): Set<number> {
  const out = new Set<number>()
  const stack = [...(children.get(id) ?? [])]
  while (stack.length) {
    const c = stack.pop()!
    if (out.has(c) || c === id) continue
    out.add(c)
    stack.push(...(children.get(c) ?? []))
  }
  return out
}

/** 杂交组合的家系：这个组合和从它一路选出来的后代（只沿选株往下，不跨到别的杂交组合） */
export function familyMembers(crossId: number, lines: LineRow[]): Set<number> {
  const bySelection = new Map<number, number[]>()
  for (const l of lines) {
    if (l.kind === 'SELECTION' && l.parentId != null) {
      const list = bySelection.get(l.parentId) ?? []
      list.push(l.id)
      bySelection.set(l.parentId, list)
    }
  }
  const out = new Set<number>()
  const stack = [...(bySelection.get(crossId) ?? [])]
  while (stack.length) {
    const c = stack.pop()!
    if (out.has(c)) continue
    out.add(c)
    stack.push(...(bySelection.get(c) ?? []))
  }
  return out
}

export interface GraphOptions {
  /** 只看这几季（为空看全部） */
  seasonIds?: Set<number> | null
  /** 显示淘汰的 */
  showDropped?: boolean
  /** 折叠的杂交组合：从它选出的后代不画，组合上标"+N" */
  collapsed?: Set<number>
  /** 只画这些品系（右侧精简版：选中品系的家系） */
  only?: Set<number> | null
  nodeW?: number
  nodeH?: number
  gapX?: number
  gapY?: number
  colGap?: number
  headH?: number
}

export interface GraphNode {
  line: LineRow
  col: number
  depth: number
  x: number
  y: number
  /** 折叠起来没画的后代个数 */
  hidden: number
}

export interface GraphEdge {
  from: number
  to: number
  role: 'mother' | 'father' | 'parent'
  path: string
}

export interface GraphColumn {
  seasonId: number | null
  title: string
  x: number
  width: number
}

export interface GraphLayout {
  nodes: GraphNode[]
  edges: GraphEdge[]
  columns: GraphColumn[]
  width: number
  height: number
}

export function layoutGraph(lines: LineRow[], seasons: Season[], o: GraphOptions = {}): GraphLayout {
  const nodeW = o.nodeW ?? 150
  const nodeH = o.nodeH ?? 40
  const gapX = o.gapX ?? 36
  const gapY = o.gapY ?? 10
  const colGap = o.colGap ?? 28
  const headH = o.headH ?? 36

  // 1. 哪些品系要画
  const hiddenBy = new Map<number, number>()
  const hidden = new Set<number>()
  for (const c of o.collapsed ?? []) {
    const members = familyMembers(c, lines)
    hiddenBy.set(c, members.size)
    members.forEach((m) => hidden.add(m))
  }
  const visible = lines.filter((l) =>
    (o.showDropped || l.status !== 'DROPPED')
    && !hidden.has(l.id)
    && (!o.only || o.only.has(l.id))
    && (!o.seasonIds || o.seasonIds.size === 0 || (l.homeSeasonId != null && o.seasonIds.has(l.homeSeasonId))))
  const byId = new Map(visible.map((l) => [l.id, l]))

  // 2. 分列：季按开始日期；原始材料不能在子代右边
  const orderedSeasons = [...seasons].sort((a, b) => a.startDate.localeCompare(b.startDate) || a.id - b.id)
  const seasonIndex = new Map(orderedSeasons.map((s, i) => [s.id, i]))
  const kids = childrenMap(visible)
  /** 品系放在哪一季（季的序号），-1 表示"还没种" */
  const slotOf = new Map<number, number>()
  for (const l of visible) {
    if (l.kind !== 'FOUNDER') slotOf.set(l.id, l.homeSeasonId != null ? seasonIndex.get(l.homeSeasonId) ?? -1 : -1)
  }
  for (const l of visible) {
    if (l.kind !== 'FOUNDER') continue
    let slot = l.homeSeasonId != null ? seasonIndex.get(l.homeSeasonId) ?? Infinity : Infinity
    for (const c of kids.get(l.id) ?? []) {
      const cs = slotOf.get(c)
      if (cs != null && cs >= 0) slot = Math.min(slot, cs)
    }
    slotOf.set(l.id, Number.isFinite(slot) ? slot : -1)
  }
  const usedSlots = [...new Set(slotOf.values())].sort((a, b) => a - b)
  const colSeasons: (Season | null)[] = usedSlots.map((i) => (i < 0 ? null : orderedSeasons[i]!))
  const colIndex = new Map(usedSlots.map((slot, i) => [slot, i]))
  const colOfLine = (l: LineRow) => colIndex.get(slotOf.get(l.id)!)!

  // 3. 同一季里的父子关系：父母在左边的小列
  const depth = new Map<number, number>()
  const depthOf = (l: LineRow, seen: Set<number>): number => {
    const known = depth.get(l.id)
    if (known != null) return known
    if (seen.has(l.id)) return 0
    seen.add(l.id)
    let d = 0
    for (const pid of parentIds(l)) {
      const p = byId.get(pid)
      if (p && colOfLine(p) === colOfLine(l)) d = Math.max(d, depthOf(p, seen) + 1)
    }
    depth.set(l.id, d)
    return d
  }
  visible.forEach((l) => depthOf(l, new Set()))

  // 4. 列的位置
  const maxDepth = colSeasons.map(() => 0)
  visible.forEach((l) => {
    const c = colOfLine(l)
    maxDepth[c] = Math.max(maxDepth[c]!, depth.get(l.id)!)
  })
  const columns: GraphColumn[] = []
  let x = 0
  colSeasons.forEach((s, i) => {
    const width = (maxDepth[i]! + 1) * nodeW + maxDepth[i]! * gapX
    columns.push({ seasonId: s?.id ?? null, title: s?.name ?? '还没种', x, width })
    x += width + colGap + gapX
  })

  // 5. 纵向：一列一列、一小列一小列地排，尽量和父母对齐（父母纵坐标的平均），没有父母的按编号
  const step = nodeH + gapY
  const yOf = new Map<number, number>()
  const groups = new Map<string, LineRow[]>()
  visible.forEach((l) => {
    const key = `${colOfLine(l)}:${depth.get(l.id)}`
    const list = groups.get(key) ?? []
    list.push(l)
    groups.set(key, list)
  })
  const nodes: GraphNode[] = []
  colSeasons.forEach((_, c) => {
    for (let d = 0; d <= maxDepth[c]!; d++) {
      const list = groups.get(`${c}:${d}`) ?? []
      const target = (l: LineRow) => {
        const ys = parentIds(l).map((p) => yOf.get(p)).filter((v): v is number => v != null)
        return ys.length ? ys.reduce((a, b) => a + b, 0) / ys.length : null
      }
      const withTarget = list.map((l) => ({ l, t: target(l) }))
      withTarget.sort((a, b) => {
        if (a.t != null && b.t != null && a.t !== b.t) return a.t - b.t
        if (a.t != null && b.t == null) return -1
        if (a.t == null && b.t != null) return 1
        return byCode(a.l, b.l)
      })
      let last = -step
      for (const { l, t } of withTarget) {
        const y = Math.max(last + step, t ?? 0)
        last = y
        yOf.set(l.id, y)
        nodes.push({
          line: l, col: c, depth: d,
          x: columns[c]!.x + d * (nodeW + gapX),
          y: headH + y,
          hidden: hiddenBy.get(l.id) ?? 0,
        })
      }
    }
  })

  // 6. 连线：从父母右边连到子代左边
  const pos = new Map(nodes.map((n) => [n.line.id, n]))
  const edges: GraphEdge[] = []
  for (const n of nodes) {
    const roles: [number | null, GraphEdge['role']][] = [
      [n.line.motherId, 'mother'], [n.line.fatherId, 'father'], [n.line.parentId, 'parent'],
    ]
    for (const [pid, role] of roles) {
      const p = pid == null ? undefined : pos.get(pid)
      if (!p) continue
      const x1 = p.x + nodeW
      const y1 = p.y + nodeH / 2
      const x2 = n.x
      const y2 = n.y + nodeH / 2
      const bend = Math.max(24, Math.abs(x2 - x1) / 2)
      edges.push({
        from: p.line.id, to: n.line.id, role,
        path: `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`,
      })
    }
  }

  const height = nodes.reduce((m, n) => Math.max(m, n.y + nodeH), headH) + 12
  return { nodes, edges, columns, width: Math.max(0, x - colGap - gapX), height }
}

/** 品系显示用的名字：名称和编号不一样时带上编号 */
export function lineLabel(l: Pick<LineRow, 'name' | 'code'>): string {
  return l.name === l.code ? l.name : `${l.name}（${l.code}）`
}

/** 最近一次种的世代，如 F3；没有世代时为空 */
export function latestGeneration(l: LineRow): string {
  for (let i = l.planted.length - 1; i >= 0; i--) {
    const g = l.planted[i]!.generation
    if (g != null) return `F${g}`
  }
  return ''
}
