import { describe, expect, it } from 'vitest';
import { generateField, DEFAULT_GEN } from '../src/model/defaults';
import {
  anchors,
  contentSize,
  deleteTrack,
  fillSlot,
  insertTrack,
  keyOf,
  locate,
  makeGrid,
  mergeRange,
  moveNode,
  newNode,
  normalizeChildren,
  resizeNode,
  setTrackCount,
  setTrackSize,
  slotOf,
  subdivide,
  sum,
  unmerge,
  walk,
} from '../src/model/grid';
import { computeLayout } from '../src/model/layout';
import type { PNode } from '../src/model/types';

function field(rows: number[], cols: number[]): PNode {
  const root = newNode();
  root.grid = makeGrid(rows, cols);
  normalizeChildren(root);
  return root;
}

/** 校验不变量：每个有网格节点的内容尺寸 = 实际尺寸；实际尺寸 ≤ 槽位 */
function checkInvariants(root: PNode) {
  const layout = computeLayout(root);
  for (const it of layout.items) {
    const cs = contentSize(it.node);
    if (cs) {
      expect(cs.w).toBeCloseTo(it.w, 4);
      expect(cs.h).toBeCloseTo(it.h, 4);
    }
    if (it.depth > 0) {
      expect(it.w).toBeLessThanOrEqual(it.slot.w + 1e-6);
      expect(it.h).toBeLessThanOrEqual(it.slot.h + 1e-6);
      expect(it.x).toBeGreaterThanOrEqual(it.slot.x - 1e-6);
      expect(it.y).toBeGreaterThanOrEqual(it.slot.y - 1e-6);
      expect(it.x + it.w).toBeLessThanOrEqual(it.slot.x + it.slot.w + 1e-6);
      expect(it.y + it.h).toBeLessThanOrEqual(it.slot.y + it.slot.h + 1e-6);
    }
  }
}

describe('网格', () => {
  it('合并与拆分', () => {
    const root = field([1, 1, 1], [2, 2, 2]);
    root.children['0,1'].data.plotNo = '102';
    const k = mergeRange(root, root.id, { r0: 0, c0: 0, r1: 1, c1: 1 });
    expect(k).toBe('0,0');
    expect(anchors(root.grid!).length).toBe(9 - 3);
    expect(root.children['0,0'].data.plotNo).toBe('102'); // 左上为空时继承有数据的单元
    expect(slotOf(root, root.children['0,0'].id)).toMatchObject({ w: 4, h: 2 });
    // 与已有合并部分相交时扩展
    mergeRange(root, root.id, { r0: 1, c0: 1, r1: 2, c1: 2 });
    expect(root.grid!.merges).toEqual([{ r: 0, c: 0, rs: 3, cs: 3 }]);
    unmerge(root, root.id, '0,0');
    expect(anchors(root.grid!).length).toBe(9);
    expect(Object.keys(root.children).length).toBe(9);
    checkInvariants(root);
  });

  it('缩小区块：两侧为路面，内部按比例缩放，且只能在原槽位内移动', () => {
    const root = field([10], [10, 10]);
    const b = root.children['0,0'];
    subdivide(root, b.id, 2, 5);
    expect(b.grid!.cols).toEqual([2, 2, 2, 2, 2]);
    resizeNode(root, b.id, 6, 10);
    expect(b.fit).toEqual({ w: 6, h: 10, x: 2, y: 0 }); // 居中，左右各 2m 路面
    expect(sum(b.grid!.cols)).toBeCloseTo(6);
    moveNode(root, b.id, 100, 0);
    expect(b.fit!.x).toBeCloseTo(4); // 限制在槽位内
    moveNode(root, b.id, -5, 0);
    expect(b.fit!.x).toBe(0);
    fillSlot(root, b.id);
    expect(b.fit).toBeNull();
    expect(sum(b.grid!.cols)).toBeCloseTo(10);
    checkInvariants(root);
  });

  it('小区内部同样可以缩小并拖动', () => {
    const root = field([10], [10]);
    const b = root.children['0,0'];
    subdivide(root, b.id, 1, 5);
    const plot = b.children['0,2'];
    resizeNode(root, plot.id, 1.2, 8);
    expect(plot.fit).toMatchObject({ w: 1.2, h: 8 });
    expect(plot.fit!.x).toBeCloseTo(0.4);
    expect(plot.fit!.y).toBeCloseTo(1);
    checkInvariants(root);
  });

  it('加大子网格时自动加宽上级，兄弟保持原尺寸', () => {
    const root = field([10], [10, 10]);
    const a = root.children['0,0'];
    const b = root.children['0,1'];
    subdivide(root, a.id, 1, 5);
    setTrackSize(root, a.id, 'col', 0, 4); // 合计 12m > 槽位 10m
    expect(root.grid!.cols[0]).toBeCloseTo(12);
    expect(a.fit).toBeNull();
    expect(root.grid!.cols[1]).toBe(10);
    expect(b.fit).toBeNull();
    // 行高变化：同一行的 b 被固定原尺寸，多出部分为路面
    setTrackSize(root, a.id, 'row', 0, 14);
    expect(root.grid!.rows[0]).toBeCloseTo(14);
    expect(b.fit).toMatchObject({ w: 10, h: 10, x: 0, y: 2 });
    checkInvariants(root);
  });

  it('子网格变小时区块变小并居中（两侧为路面）', () => {
    const root = field([10], [10]);
    const a = root.children['0,0'];
    subdivide(root, a.id, 1, 5);
    setTrackSize(root, a.id, 'col', 4, 0.5); // 合计 8.5m
    expect(a.fit).toMatchObject({ w: 8.5, h: 10 });
    expect(a.fit!.x).toBeCloseTo(0.75);
    checkInvariants(root);
  });

  it('插入/删除行列保持数据与合并', () => {
    const root = field([1, 1, 1], [1, 1]);
    root.children['2,1'].data.name = 'x';
    mergeRange(root, root.id, { r0: 0, c0: 0, r1: 1, c1: 0 });
    insertTrack(root, root.id, 'row', 1, 1);
    expect(root.grid!.rows.length).toBe(4);
    expect(root.grid!.merges).toEqual([{ r: 0, c: 0, rs: 3, cs: 1 }]);
    expect(root.children['3,1'].data.name).toBe('x');
    deleteTrack(root, root.id, 'row', 0);
    expect(root.grid!.merges).toEqual([{ r: 0, c: 0, rs: 2, cs: 1 }]);
    expect(root.children['2,1'].data.name).toBe('x');
    deleteTrack(root, root.id, 'col', 0);
    expect(root.grid!.merges).toEqual([]);
    expect(root.grid!.cols.length).toBe(1);
    expect(root.children['2,0'].data.name).toBe('x');
    checkInvariants(root);
  });

  it('修改行列数：保持总尺寸并均分，裁剪越界合并', () => {
    const root = field([2, 2], [3, 3, 3]);
    mergeRange(root, root.id, { r0: 0, c0: 1, r1: 0, c1: 2 });
    setTrackCount(root, root.id, 'col', 2);
    expect(root.grid!.cols).toEqual([4.5, 4.5]);
    expect(root.grid!.merges).toEqual([]);
    expect(Object.keys(root.children).sort()).toEqual(['0,0', '0,1', '1,0', '1,1']);
    checkInvariants(root);
  });

  it('生成的种植图满足不变量', () => {
    const f = generateField(DEFAULT_GEN, []);
    checkInvariants(f);
    let plots = 0;
    walk(f, (n, d) => d === 2 && n.kind === 'normal' && plots++);
    expect(plots).toBe(DEFAULT_GEN.reps * DEFAULT_GEN.repCols * DEFAULT_GEN.repRows);
    const rep1 = Object.values(f.children).find((c) => c.data.rep === '1')!;
    expect(rep1.children[keyOf(0, 0)].data.plotNo).toBe('101');
    expect(locate(f, rep1.id)?.depth).toBe(1);
  });

  it('多层级随机操作后仍满足不变量', () => {
    const root = field([5, 5], [5, 5, 5]);
    let seed = 7;
    const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (const ch of Object.values(root.children)) {
      subdivide(root, ch.id, 2, 3);
      for (const p of Object.values(ch.children)) subdivide(root, p.id, 1, 4);
    }
    const all: string[] = [];
    walk(root, (n) => all.push(n.id));
    for (let i = 0; i < 300; i++) {
      const id = all[Math.floor(rnd() * all.length)];
      const loc = locate(root, id);
      if (!loc) continue;
      const op = Math.floor(rnd() * 4);
      if (op === 0 && loc.parent) {
        const s = slotOf(root, id)!;
        resizeNode(root, id, s.w * (0.3 + rnd()), s.h * (0.3 + rnd()));
      } else if (op === 1 && loc.node.grid) {
        const n = loc.node.grid.cols.length;
        setTrackSize(root, id, 'col', Math.floor(rnd() * n), 0.2 + rnd() * 3);
      } else if (op === 2 && loc.node.grid) {
        const g = loc.node.grid;
        const r0 = Math.floor(rnd() * g.rows.length);
        const c0 = Math.floor(rnd() * g.cols.length);
        mergeRange(root, id, { r0, c0, r1: Math.min(g.rows.length - 1, r0 + 1), c1: c0 });
      } else if (op === 3 && loc.node.fit) {
        moveNode(root, id, rnd() * 3, rnd() * 3);
      }
      checkInvariants(root);
    }
  });
});
