import type { CellKind, Fit, Grid, Merge, PNode } from './types';

/**
 * 通用网格操作。试验田、区块、小区、子块使用同一套结构：
 *  - grid.rows / grid.cols：每行高度、每列宽度（米），确定子节点的“槽位”
 *  - node.fit：子节点实际尺寸与在槽位中的偏移；比槽位小时其余部分为“路面”
 * 不变量：有网格的节点，其行高之和 = 实际高度，列宽之和 = 实际宽度。
 */

export const EPS = 1e-6;
export const MIN_SIZE = 0.05;

let uidCounter = 0;
export function uid(prefix = 'n'): string {
  uidCounter = (uidCounter + 1) % 1e6;
  return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}${uidCounter.toString(36)}`;
}

export const near = (a: number, b: number, eps = 1e-4) => Math.abs(a - b) <= eps;
export const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));
export const round = (v: number, d = 3) => {
  const p = 10 ** d;
  return Math.round(v * p) / p;
};

export function sum(a: number[], from = 0, to = a.length): number {
  let s = 0;
  for (let i = from; i < to; i++) s += a[i];
  return s;
}

export const keyOf = (r: number, c: number) => `${r},${c}`;
export function parseKey(key: string): [number, number] {
  const [r, c] = key.split(',').map(Number);
  return [r, c];
}

export function newNode(kind: CellKind = 'normal', data: Record<string, string> = {}): PNode {
  return { id: uid(), kind, data, fit: null, grid: null, children: {} };
}

export function makeGrid(rows: number[], cols: number[], merges: Merge[] = []): Grid {
  return { rows: [...rows], cols: [...cols], merges: merges.map((m) => ({ ...m })) };
}

/** 网格中所有单元（合并区域按锚点算一个），按行列排序 */
export function anchors(g: Grid): Merge[] {
  const covered = new Set<string>();
  const out: Merge[] = [];
  for (const m of g.merges) {
    out.push({ ...m });
    for (let r = m.r; r < m.r + m.rs; r++) for (let c = m.c; c < m.c + m.cs; c++) covered.add(keyOf(r, c));
  }
  for (let r = 0; r < g.rows.length; r++)
    for (let c = 0; c < g.cols.length; c++) if (!covered.has(keyOf(r, c))) out.push({ r, c, rs: 1, cs: 1 });
  out.sort((a, b) => a.r - b.r || a.c - b.c);
  return out;
}

export function mergeAt(g: Grid, r: number, c: number): Merge | undefined {
  return g.merges.find((m) => r >= m.r && r < m.r + m.rs && c >= m.c && c < m.c + m.cs);
}

/** 键对应的单元范围 */
export function spanOf(g: Grid, key: string): Merge {
  const [r, c] = parseKey(key);
  const m = g.merges.find((x) => x.r === r && x.c === c);
  return m ? { ...m } : { r, c, rs: 1, cs: 1 };
}

/** 单元槽位（相对于容器内容原点） */
export function slotRect(g: Grid, m: Merge): Fit {
  return {
    x: sum(g.cols, 0, m.c),
    y: sum(g.rows, 0, m.r),
    w: sum(g.cols, m.c, m.c + m.cs),
    h: sum(g.rows, m.r, m.r + m.rs),
  };
}

export function contentSize(node: PNode): { w: number; h: number } | null {
  if (!node.grid) return null;
  return { w: sum(node.grid.cols), h: sum(node.grid.rows) };
}

/** 使 children 与网格单元一一对应 */
export function normalizeChildren(node: PNode, kindForNew?: (m: Merge) => CellKind): void {
  if (!node.grid) {
    node.children = {};
    return;
  }
  const keep = new Set<string>();
  for (const a of anchors(node.grid)) {
    const k = keyOf(a.r, a.c);
    keep.add(k);
    if (!node.children[k]) node.children[k] = newNode(kindForNew ? kindForNew(a) : 'normal');
  }
  for (const k of Object.keys(node.children)) if (!keep.has(k)) delete node.children[k];
}

/** 按比例缩放节点内部（网格与所有后代的实际尺寸） */
export function scaleContent(node: PNode, sx: number, sy: number): void {
  if (!node.grid || !isFinite(sx) || !isFinite(sy) || sx <= 0 || sy <= 0) return;
  if (near(sx, 1, 1e-9) && near(sy, 1, 1e-9)) return;
  node.grid.rows = node.grid.rows.map((v) => v * sy);
  node.grid.cols = node.grid.cols.map((v) => v * sx);
  for (const child of Object.values(node.children)) {
    if (child.fit) child.fit = { x: child.fit.x * sx, y: child.fit.y * sy, w: child.fit.w * sx, h: child.fit.h * sy };
    scaleContent(child, sx, sy);
  }
}

/** 槽位变化后修正子节点：超出则收缩，内部网格按比例缩放 */
export function reconcileChild(child: PNode, slot: Fit): void {
  if (child.fit) {
    const w = Math.min(child.fit.w, slot.w);
    const h = Math.min(child.fit.h, slot.h);
    if (near(w, slot.w) && near(h, slot.h)) child.fit = null;
    else child.fit = { w, h, x: clamp(child.fit.x, 0, slot.w - w), y: clamp(child.fit.y, 0, slot.h - h) };
  }
  const act = child.fit ?? slot;
  const cs = contentSize(child);
  if (cs && (!near(cs.w, act.w) || !near(cs.h, act.h))) scaleContent(child, act.w / cs.w, act.h / cs.h);
}

export function reconcileChildren(node: PNode): void {
  if (!node.grid) return;
  for (const a of anchors(node.grid)) {
    const child = node.children[keyOf(a.r, a.c)];
    if (child) reconcileChild(child, slotRect(node.grid, a));
  }
}

export interface Loc {
  node: PNode;
  parent: PNode | null;
  key: string | null;
  depth: number;
  path: PNode[];
}

export function locate(root: PNode, id: string): Loc | null {
  const path: PNode[] = [];
  const walk = (n: PNode, parent: PNode | null, key: string | null, depth: number): Loc | null => {
    path.push(n);
    if (n.id === id) return { node: n, parent, key, depth, path: [...path] };
    for (const [k, ch] of Object.entries(n.children)) {
      const r = walk(ch, n, k, depth + 1);
      if (r) return r;
    }
    path.pop();
    return null;
  };
  return walk(root, null, null, 0);
}

/** 节点槽位（根节点为自身内容尺寸） */
export function slotOf(root: PNode, id: string): Fit | null {
  const loc = locate(root, id);
  if (!loc) return null;
  if (!loc.parent || !loc.key) {
    const cs = contentSize(loc.node) ?? { w: 0, h: 0 };
    return { x: 0, y: 0, ...cs };
  }
  return slotRect(loc.parent.grid!, spanOf(loc.parent.grid!, loc.key));
}

export function actualSize(node: PNode, slot: Fit): { w: number; h: number } {
  return node.fit ? { w: node.fit.w, h: node.fit.h } : { w: slot.w, h: slot.h };
}

/** 设置实际尺寸；默认保持中心（首次缩小时居中，两侧为路面） */
export function setFit(node: PNode, w: number, h: number, slot: Fit, pos?: { x: number; y: number }): void {
  w = Math.min(w, slot.w);
  h = Math.min(h, slot.h);
  if (near(w, slot.w) && near(h, slot.h)) {
    node.fit = null;
    return;
  }
  let x: number;
  let y: number;
  if (pos) {
    x = pos.x;
    y = pos.y;
  } else if (node.fit) {
    x = node.fit.x + (node.fit.w - w) / 2;
    y = node.fit.y + (node.fit.h - h) / 2;
  } else {
    x = (slot.w - w) / 2;
    y = (slot.h - h) / 2;
  }
  node.fit = { w, h, x: clamp(x, 0, slot.w - w), y: clamp(y, 0, slot.h - h) };
}

/** 父网格扩展：同一行/列中原本填满的兄弟节点保持原尺寸（多出部分为路面） */
function growTracks(parent: PNode, m: Merge, dw: number, dh: number, exceptKey: string): void {
  const g = parent.grid!;
  const ci = m.c + m.cs - 1;
  const ri = m.r + m.rs - 1;
  const pinned: [PNode, Merge][] = [];
  for (const a of anchors(g)) {
    const k = keyOf(a.r, a.c);
    if (k === exceptKey) continue;
    const hitC = dw > EPS && a.c <= ci && ci < a.c + a.cs;
    const hitR = dh > EPS && a.r <= ri && ri < a.r + a.rs;
    if (!hitC && !hitR) continue;
    const ch = parent.children[k];
    if (ch && !ch.fit) {
      const s = slotRect(g, a);
      ch.fit = { x: 0, y: 0, w: s.w, h: s.h };
      pinned.push([ch, a]);
    }
  }
  if (dw > EPS) g.cols[ci] += dw;
  if (dh > EPS) g.rows[ri] += dh;
  for (const [ch, a] of pinned) {
    const s = slotRect(g, a);
    ch.fit!.x = (s.w - ch.fit!.w) / 2;
    ch.fit!.y = (s.h - ch.fit!.h) / 2;
  }
}

/**
 * 把节点放到指定实际尺寸。小于槽位 → 其余为路面；
 * 大于槽位 → 自动加宽父级对应的行/列（并逐级向上传递）。
 * 注意：不缩放节点内部，调用者负责。
 */
export function placeNode(root: PNode, id: string, w: number, h: number, pos?: { x: number; y: number }): void {
  const loc = locate(root, id);
  if (!loc || !loc.parent || !loc.key) return;
  const parent = loc.parent;
  const g = parent.grid!;
  const m = spanOf(g, loc.key);
  let slot = slotRect(g, m);
  const dw = w - slot.w;
  const dh = h - slot.h;
  if (dw > 1e-4 || dh > 1e-4) {
    growTracks(parent, m, Math.max(0, dw), Math.max(0, dh), loc.key);
    slot = slotRect(g, m);
    setFit(loc.node, w, h, slot, pos);
    afterContentChanged(root, parent.id);
  } else {
    setFit(loc.node, w, h, slot, pos);
  }
}

/** 节点网格（行列尺寸/合并）改变后调用：修正子节点并让自身尺寸与内容一致 */
export function afterContentChanged(root: PNode, id: string): void {
  const loc = locate(root, id);
  if (!loc) return;
  const node = loc.node;
  normalizeChildren(node);
  reconcileChildren(node);
  if (!loc.parent || !loc.key) return;
  const cs = contentSize(node);
  if (!cs) return;
  const slot = slotRect(loc.parent.grid!, spanOf(loc.parent.grid!, loc.key));
  const act = actualSize(node, slot);
  if (!near(cs.w, act.w) || !near(cs.h, act.h)) placeNode(root, id, cs.w, cs.h);
}

/** 用户直接设定某节点尺寸（内部按比例缩放） */
export function resizeNode(root: PNode, id: string, w: number, h: number, pos?: { x: number; y: number }): void {
  const loc = locate(root, id);
  if (!loc) return;
  w = Math.max(MIN_SIZE, w);
  h = Math.max(MIN_SIZE, h);
  if (!loc.parent) {
    const cs = contentSize(loc.node);
    if (cs) scaleContent(loc.node, w / cs.w, h / cs.h);
    return;
  }
  const slot = slotOf(root, id)!;
  const act = actualSize(loc.node, slot);
  const cs = contentSize(loc.node);
  if (cs) scaleContent(loc.node, w / act.w, h / act.h);
  placeNode(root, id, w, h, pos);
}

/** 恢复填满槽位 */
export function fillSlot(root: PNode, id: string): void {
  const slot = slotOf(root, id);
  if (!slot) return;
  resizeNode(root, id, slot.w, slot.h);
}

/** 在槽位内移动 */
export function moveNode(root: PNode, id: string, x: number, y: number): void {
  const loc = locate(root, id);
  if (!loc || !loc.node.fit) return;
  const slot = slotOf(root, id)!;
  const f = loc.node.fit;
  loc.node.fit = { ...f, x: clamp(x, 0, slot.w - f.w), y: clamp(y, 0, slot.h - f.h) };
}

export type Axis = 'row' | 'col';
const tracks = (g: Grid, axis: Axis) => (axis === 'row' ? g.rows : g.cols);

/** 修改单个行高/列宽 */
export function setTrackSize(root: PNode, id: string, axis: Axis, index: number, size: number): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  tracks(loc.node.grid, axis)[index] = Math.max(MIN_SIZE, size);
  afterContentChanged(root, id);
}

/** 统一设置所有行高/列宽 */
export function setAllTracks(root: PNode, id: string, axis: Axis, size: number): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  const arr = tracks(loc.node.grid, axis);
  for (let i = 0; i < arr.length; i++) arr[i] = Math.max(MIN_SIZE, size);
  afterContentChanged(root, id);
}

/** 均分（总尺寸不变） */
export function equalizeTracks(root: PNode, id: string, axis: Axis): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  const arr = tracks(loc.node.grid, axis);
  const avg = sum(arr) / arr.length;
  for (let i = 0; i < arr.length; i++) arr[i] = avg;
  afterContentChanged(root, id);
}

function clipMerges(g: Grid): void {
  const nr = g.rows.length;
  const nc = g.cols.length;
  g.merges = g.merges
    .filter((m) => m.r < nr && m.c < nc)
    .map((m) => ({ ...m, rs: Math.min(m.rs, nr - m.r), cs: Math.min(m.cs, nc - m.c) }))
    .filter((m) => m.rs * m.cs > 1);
}

/** 设置行数/列数：保持总尺寸不变并均分 */
export function setTrackCount(root: PNode, id: string, axis: Axis, n: number): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  n = Math.max(1, Math.min(500, Math.floor(n)));
  const g = loc.node.grid;
  const total = sum(tracks(g, axis));
  const arr = Array.from({ length: n }, () => total / n);
  if (axis === 'row') g.rows = arr;
  else g.cols = arr;
  clipMerges(g);
  afterContentChanged(root, id);
}

/** 在 index 前插入一行/列 */
export function insertTrack(root: PNode, id: string, axis: Axis, index: number, size?: number): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  const node = loc.node;
  const g = node.grid!;
  const arr = tracks(g, axis);
  index = clamp(index, 0, arr.length);
  const ref = arr[Math.min(index, arr.length - 1)] ?? 1;
  arr.splice(index, 0, size ?? ref);
  for (const m of g.merges) {
    if (axis === 'row') {
      if (m.r >= index) m.r++;
      else if (index < m.r + m.rs) m.rs++;
    } else {
      if (m.c >= index) m.c++;
      else if (index < m.c + m.cs) m.cs++;
    }
  }
  const nc: Record<string, PNode> = {};
  for (const [k, ch] of Object.entries(node.children)) {
    let [r, c] = parseKey(k);
    if (axis === 'row' && r >= index) r++;
    if (axis === 'col' && c >= index) c++;
    nc[keyOf(r, c)] = ch;
  }
  node.children = nc;
  afterContentChanged(root, id);
}

/** 删除第 index 行/列 */
export function deleteTrack(root: PNode, id: string, axis: Axis, index: number): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  const node = loc.node;
  const g = node.grid!;
  const arr = tracks(g, axis);
  if (arr.length <= 1 || index < 0 || index >= arr.length) return;
  const nc: Record<string, PNode> = {};
  for (const [k, ch] of Object.entries(node.children)) {
    let [r, c] = parseKey(k);
    const pos = axis === 'row' ? r : c;
    if (pos === index) {
      const m = g.merges.find((x) => x.r === r && x.c === c);
      const span = m ? (axis === 'row' ? m.rs : m.cs) : 1;
      if (span <= 1) continue;
    } else if (pos > index) {
      if (axis === 'row') r--;
      else c--;
    }
    nc[keyOf(r, c)] = ch;
  }
  for (const m of g.merges) {
    if (axis === 'row') {
      if (m.r > index) m.r--;
      else if (index < m.r + m.rs) m.rs--;
    } else {
      if (m.c > index) m.c--;
      else if (index < m.c + m.cs) m.cs--;
    }
  }
  g.merges = g.merges.filter((m) => m.rs > 0 && m.cs > 0 && m.rs * m.cs > 1);
  arr.splice(index, 1);
  node.children = nc;
  afterContentChanged(root, id);
}

export interface Range {
  r0: number;
  c0: number;
  r1: number;
  c1: number;
}

const intersects = (a: Range, m: Merge) => !(m.r > a.r1 || m.r + m.rs - 1 < a.r0 || m.c > a.c1 || m.c + m.cs - 1 < a.c0);

/** 扩展范围直到不与合并区域部分相交 */
export function expandRange(g: Grid, rg: Range): Range {
  const r = { ...rg };
  let changed = true;
  while (changed) {
    changed = false;
    for (const m of g.merges) {
      if (!intersects(r, m)) continue;
      const nr0 = Math.min(r.r0, m.r);
      const nc0 = Math.min(r.c0, m.c);
      const nr1 = Math.max(r.r1, m.r + m.rs - 1);
      const nc1 = Math.max(r.c1, m.c + m.cs - 1);
      if (nr0 !== r.r0 || nc0 !== r.c0 || nr1 !== r.r1 || nc1 !== r.c1) {
        Object.assign(r, { r0: nr0, c0: nc0, r1: nr1, c1: nc1 });
        changed = true;
      }
    }
  }
  return r;
}

/** 键集合的外接范围 */
export function rangeOfKeys(g: Grid, keys: string[]): Range | null {
  if (!keys.length) return null;
  let r0 = Infinity;
  let c0 = Infinity;
  let r1 = -Infinity;
  let c1 = -Infinity;
  for (const k of keys) {
    const m = spanOf(g, k);
    r0 = Math.min(r0, m.r);
    c0 = Math.min(c0, m.c);
    r1 = Math.max(r1, m.r + m.rs - 1);
    c1 = Math.max(c1, m.c + m.cs - 1);
  }
  return { r0, c0, r1, c1 };
}

/** 与范围相交的所有单元键 */
export function keysInRange(g: Grid, rg: Range): string[] {
  return anchors(g)
    .filter((m) => intersects(rg, m))
    .map((m) => keyOf(m.r, m.c));
}

const hasData = (n: PNode) => Object.values(n.data).some((v) => v !== '' && v != null);

/** 合并范围内的单元，返回锚点键 */
export function mergeRange(root: PNode, id: string, rg: Range): string | null {
  const loc = locate(root, id);
  if (!loc?.node.grid) return null;
  const node = loc.node;
  const g = node.grid!;
  const r = expandRange(g, {
    r0: Math.min(rg.r0, rg.r1),
    c0: Math.min(rg.c0, rg.c1),
    r1: Math.max(rg.r0, rg.r1),
    c1: Math.max(rg.c0, rg.c1),
  });
  if (r.r0 === r.r1 && r.c0 === r.c1) return keyOf(r.r0, r.c0);
  const anchorKey = keyOf(r.r0, r.c0);
  const inRange = keysInRange(g, r);
  const anchor = node.children[anchorKey] ?? newNode();
  if (!hasData(anchor)) {
    const donor = inRange.map((k) => node.children[k]).find((n) => n && n !== anchor && hasData(n));
    if (donor) {
      anchor.data = { ...donor.data };
      anchor.kind = donor.kind;
    }
  }
  for (const k of inRange) if (k !== anchorKey) delete node.children[k];
  anchor.fit = null;
  node.children[anchorKey] = anchor;
  g.merges = g.merges.filter((m) => !intersects(r, m));
  g.merges.push({ r: r.r0, c: r.c0, rs: r.r1 - r.r0 + 1, cs: r.c1 - r.c0 + 1 });
  afterContentChanged(root, id);
  return anchorKey;
}

/** 拆分合并单元 */
export function unmerge(root: PNode, id: string, key: string): void {
  const loc = locate(root, id);
  if (!loc?.node.grid) return;
  const g = loc.node.grid;
  const [r, c] = parseKey(key);
  g.merges = g.merges.filter((m) => !(m.r === r && m.c === c));
  afterContentChanged(root, id);
}

/** 细分：在节点内部建立 nr × nc 网格 */
export function subdivide(root: PNode, id: string, nr: number, nc: number): void {
  const loc = locate(root, id);
  if (!loc) return;
  const slot = slotOf(root, id)!;
  const size = actualSize(loc.node, slot);
  nr = Math.max(1, Math.floor(nr));
  nc = Math.max(1, Math.floor(nc));
  loc.node.grid = makeGrid(
    Array.from({ length: nr }, () => size.h / nr),
    Array.from({ length: nc }, () => size.w / nc),
  );
  loc.node.children = {};
  normalizeChildren(loc.node);
}

export function clearSubdivision(root: PNode, id: string): void {
  const loc = locate(root, id);
  if (!loc || !loc.parent) return;
  loc.node.grid = null;
  loc.node.children = {};
}

/** 遍历 */
export function walk(root: PNode, fn: (n: PNode, depth: number, path: PNode[], key: string | null) => void): void {
  const path: PNode[] = [];
  const rec = (n: PNode, depth: number, key: string | null) => {
    path.push(n);
    fn(n, depth, path, key);
    if (n.grid) {
      for (const a of anchors(n.grid)) {
        const k = keyOf(a.r, a.c);
        const ch = n.children[k];
        if (ch) rec(ch, depth + 1, k);
      }
    }
    path.pop();
  };
  rec(root, 0, null);
}

export function cloneTree(n: PNode, newIds = true): PNode {
  return {
    id: newIds ? uid() : n.id,
    kind: n.kind,
    data: { ...n.data },
    fit: n.fit ? { ...n.fit } : null,
    grid: n.grid ? makeGrid(n.grid.rows, n.grid.cols, n.grid.merges) : null,
    children: Object.fromEntries(Object.entries(n.children).map(([k, c]) => [k, cloneTree(c, newIds)])),
  };
}
