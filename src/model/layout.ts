import { anchors, contentSize, keyOf, slotRect, sum } from './grid';
import type { Fit, Merge, PNode } from './types';

export interface LayoutItem {
  id: string;
  node: PNode;
  depth: number;
  parentId: string | null;
  key: string | null;
  span: Merge | null;
  /** 实际矩形（绝对坐标，米） */
  x: number;
  y: number;
  w: number;
  h: number;
  /** 槽位矩形 */
  slot: Fit;
}

export interface Layout {
  items: LayoutItem[];
  byId: Map<string, LayoutItem>;
  width: number;
  height: number;
}

/** 计算所有节点的绝对位置。若内容与实际尺寸不一致则按比例显示（容错）。 */
export function computeLayout(root: PNode): Layout {
  const items: LayoutItem[] = [];
  const byId = new Map<string, LayoutItem>();
  const cs = contentSize(root) ?? { w: 10, h: 10 };

  const rec = (node: PNode, depth: number, parentId: string | null, key: string | null, span: Merge | null, slot: Fit) => {
    const x = node.fit && parentId ? slot.x + node.fit.x : slot.x;
    const y = node.fit && parentId ? slot.y + node.fit.y : slot.y;
    const w = node.fit && parentId ? node.fit.w : slot.w;
    const h = node.fit && parentId ? node.fit.h : slot.h;
    const item: LayoutItem = { id: node.id, node, depth, parentId, key, span, x, y, w, h, slot };
    items.push(item);
    byId.set(node.id, item);
    if (!node.grid) return;
    const g = node.grid;
    const tw = sum(g.cols) || 1;
    const th = sum(g.rows) || 1;
    const sx = w / tw;
    const sy = h / th;
    for (const a of anchors(g)) {
      const k = keyOf(a.r, a.c);
      const ch = node.children[k];
      if (!ch) continue;
      const s = slotRect(g, a);
      rec(ch, depth + 1, node.id, k, a, { x: x + s.x * sx, y: y + s.y * sy, w: s.w * sx, h: s.h * sy });
    }
  };
  rec(root, 0, null, null, null, { x: 0, y: 0, w: cs.w, h: cs.h });
  return { items, byId, width: cs.w, height: cs.h };
}

/** 从根到节点的 id 路径 */
export function pathIds(layout: Layout, id: string): string[] {
  const out: string[] = [];
  let cur = layout.byId.get(id);
  while (cur) {
    out.unshift(cur.id);
    cur = cur.parentId ? layout.byId.get(cur.parentId) : undefined;
  }
  return out;
}
