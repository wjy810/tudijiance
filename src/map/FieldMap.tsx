import { produce } from 'immer';
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as RPointerEvent } from 'react';
import { Icon } from '../components/Icon';
import { collectPlots } from '../model/calc';
import { displayValue, heatColor, nodeTitle, numericFields, repColor, varietyColor } from '../model/display';
import { levelOfDepth } from '../model/fields';
import {
  MIN_SIZE,
  clamp,
  keysInRange,
  locate,
  moveNode,
  parseKey,
  placeNode,
  rangeOfKeys,
  round,
  scaleContent,
  setTrackSize,
  sum,
} from '../model/grid';
import { computeLayout, pathIds, type Layout, type LayoutItem } from '../model/layout';
import type { Fit, Project } from '../model/types';
import { projectActions, uiStore, useProject, useStore, viewStore, type ViewState } from '../store/store';
import { Compass, ScaleBar } from './Compass';

type Pt = { x: number; y: number };

type Drag =
  | { type: 'pending'; id: string | null; s0: Pt; shift: boolean; ctrl: boolean; movable: boolean; f0: Pt }
  | { type: 'pan'; s0: Pt; pan0: Pt }
  | { type: 'rotate'; a0: number; rot0: number; pan0: Pt; pivot: Pt }
  | { type: 'move'; id: string; f0: Pt; fit0: Fit; start: Project }
  | { type: 'resize'; id: string; dir: string; f0: Pt; fit0: Fit; slot: Fit; start: Project }
  | { type: 'track'; cid: string; axis: 'row' | 'col'; index: number; f0: Pt; size0: number; scale: number; start: Project }
  | { type: 'pinch'; c0: Pt; d0: number; a0: number; view0: ViewState };

const DEG = Math.PI / 180;
const rotPt = (p: Pt, deg: number): Pt => {
  const c = Math.cos(deg * DEG);
  const s = Math.sin(deg * DEG);
  return { x: p.x * c - p.y * s, y: p.x * s + p.y * c };
};
const normDeg = (d: number) => ((((d + 180) % 360) + 360) % 360) - 180;
const emWidth = (s: string) => [...s].reduce((w, ch) => w + (ch.charCodeAt(0) < 256 ? 0.6 : 1), 0);

export function setView(patch: Partial<ViewState>) {
  viewStore.set((v) => ({ ...v, ...patch }));
}

/** 视角操作（锁定时无效） */
export const viewOps = {
  rotateBy(deg: number, pivot: Pt) {
    const v = viewStore.get();
    if (v.locked) return;
    const p = rotPt({ x: v.panX - pivot.x, y: v.panY - pivot.y }, deg);
    setView({ rot: normDeg(v.rot + deg), panX: pivot.x + p.x, panY: pivot.y + p.y });
  },
  zoomBy(k: number, at: Pt) {
    const v = viewStore.get();
    if (v.locked) return;
    const z = clamp(v.zoom * k, 0.5, 2000);
    const kk = z / v.zoom;
    setView({ zoom: z, panX: at.x + (v.panX - at.x) * kk, panY: at.y + (v.panY - at.y) * kk });
  },
};

export function FieldMap() {
  const project = useProject();
  const ui = useStore(uiStore);
  const view = useStore(viewStore);
  const layout = useMemo(() => computeLayout(project.field), [project.field]);
  const wrapRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const gRef = useRef<SVGGElement>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const drag = useRef<Drag | null>(null);
  const pointers = useRef(new Map<number, Pt>());
  const [hoverId, setHoverId] = useState<string | null>(null);
  const lastTap = useRef<{ t: number; id: string | null; x: number; y: number } | null>(null);

  const vmap = useMemo(() => new Map(project.varieties.map((v) => [v.id, v])), [project.varieties]);
  const vindex = useMemo(() => new Map(project.varieties.map((v, i) => [v.id, i])), [project.varieties]);

  /* ---- 尺寸 ---- */
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    setSize({ w: el.clientWidth, h: el.clientHeight });
    return () => ro.disconnect();
  }, []);

  const fit = useCallback(
    (rot = viewStore.get().rot, force = false) => {
      const v = viewStore.get();
      if (v.locked && !force && v.ready) return;
      if (!size.w || !size.h) return;
      const W = layout.width;
      const H = layout.height;
      const c = Math.abs(Math.cos(rot * DEG));
      const s = Math.abs(Math.sin(rot * DEG));
      const bw = W * c + H * s;
      const bh = W * s + H * c;
      // 顶部留出工具栏、底部留出提示栏；四周留出标尺与方位标注（像素）
      const small = size.w < 700;
      const top = small ? 92 : 84;
      const bottom = small ? 4 : 30;
      const pad = small ? 50 : 62;
      const availH = size.h - top - bottom;
      const zoom = Math.max(0.5, Math.min((size.w - pad * 2) / bw, (availH - pad * 2) / bh));
      const center = rotPt({ x: (layout.width / 2) * zoom, y: (layout.height / 2) * zoom }, rot);
      setView({ rot, zoom, panX: size.w / 2 - center.x, panY: top + availH / 2 - center.y, ready: true });
    },
    [layout.width, layout.height, size.w, size.h],
  );

  useEffect(() => {
    if (!view.ready && size.w > 0) fit(view.rot, true);
  }, [size.w, size.h, view.ready, fit, view.rot]);

  /* ---- 保证当前层级/选择有效 ---- */
  useEffect(() => {
    if (!layout.byId.has(ui.activeId)) {
      uiStore.set((s) => ({ ...s, activeId: project.field.id, selection: [], anchorKey: null }));
      return;
    }
    const act = layout.byId.get(ui.activeId)!;
    const valid = ui.selection.filter((k) => act.node.children[k]);
    if (valid.length !== ui.selection.length) uiStore.set((s) => ({ ...s, selection: valid }));
  }, [layout, ui.activeId, ui.selection, project.field.id]);

  const active = layout.byId.get(ui.activeId) ?? layout.items[0];
  const activeDepth = pathIds(layout, active.id).length - 1;

  /* ---- 坐标转换 ---- */
  const local = (e: { clientX: number; clientY: number }): Pt => {
    const r = svgRef.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };
  const toField = (e: { clientX: number; clientY: number }): Pt => {
    const g = gRef.current!;
    const m = g.getScreenCTM();
    if (!m) return { x: 0, y: 0 };
    const p = new DOMPoint(e.clientX, e.clientY).matrixTransform(m.inverse());
    return { x: p.x, y: p.y };
  };

  /* ---- 着色 ---- */
  const heatRange = useMemo(() => {
    if (['variety', 'rep', 'none'].includes(ui.colorBy)) return null;
    const vals = collectPlots(project)
      .map((p) => Number(p.node.data[ui.colorBy]))
      .filter((v) => p0(v));
    if (!vals.length) return { min: 0, max: 0, n: 0 };
    return { min: Math.min(...vals), max: Math.max(...vals), n: vals.length };
  }, [project, ui.colorBy]);

  const fillOf = (it: LayoutItem): string => {
    const n = it.node;
    if (n.kind === 'road') return 'url(#pat-road)';
    if (n.kind === 'protect') return 'url(#pat-protect)';
    if (n.kind === 'blank') return 'var(--map-blank)';
    if (it.depth === 0) return 'var(--map-soil)';
    if (it.depth === 1) return 'var(--map-block)';
    if (it.depth >= 3) return (it.span!.r + it.span!.c) % 2 ? 'rgba(255,255,255,0.28)' : 'rgba(255,255,255,0.08)';
    if (ui.colorBy === 'variety') {
      const v = vmap.get(n.data.variety);
      return v ? varietyColor(vindex.get(v.id)!, v.role) : 'var(--map-plot)';
    }
    if (ui.colorBy === 'rep') {
      const b = it.parentId ? layout.byId.get(it.parentId) : null;
      const rep = Number(b?.node.data.rep);
      return rep ? repColor(rep) : 'var(--map-plot)';
    }
    if (ui.colorBy === 'none' || !heatRange) return 'var(--map-plot)';
    const val = Number(n.data[ui.colorBy]);
    if (!p0(val) || n.data[ui.colorBy] === '' || n.data[ui.colorBy] == null) return 'var(--map-empty)';
    const t = heatRange.max > heatRange.min ? (val - heatRange.min) / (heatRange.max - heatRange.min) : 0.5;
    return heatColor(t);
  };

  /* ---- 选择 ---- */
  const pickTarget = (clickedId: string): LayoutItem | null => {
    const path = pathIds(layout, clickedId);
    const td = activeDepth + 1;
    const id = path.length - 1 >= td ? path[td] : path[path.length - 1];
    const it = layout.byId.get(id);
    if (!it || it.depth === 0) return null;
    return it;
  };

  const select = (clickedId: string | null, shift: boolean, ctrl: boolean) => {
    const it = clickedId ? pickTarget(clickedId) : null;
    if (!it || !it.parentId || !it.key) {
      uiStore.set((s) => ({ ...s, selection: [], anchorKey: null }));
      return;
    }
    const container = layout.byId.get(it.parentId)!;
    const key = it.key;
    uiStore.set((s) => {
      if (s.activeId !== container.id) return { ...s, activeId: container.id, selection: [key], anchorKey: key };
      if (shift && s.anchorKey && container.node.grid && container.node.children[s.anchorKey]) {
        const rg = rangeOfKeys(container.node.grid, [s.anchorKey, key])!;
        return { ...s, selection: keysInRange(container.node.grid, rg) };
      }
      if (ctrl) {
        const has = s.selection.includes(key);
        return { ...s, selection: has ? s.selection.filter((k) => k !== key) : [...s.selection, key], anchorKey: key };
      }
      return { ...s, selection: [key], anchorKey: key };
    });
  };

  const enter = (clickedId: string) => {
    const it = pickTarget(clickedId);
    if (!it) return;
    if (it.node.grid) uiStore.set((s) => ({ ...s, activeId: it.id, selection: [], anchorKey: null }));
    else select(clickedId, false, false);
  };

  const goUp = () => {
    const it = layout.byId.get(ui.activeId);
    if (!it?.parentId) {
      uiStore.set((s) => ({ ...s, selection: [], anchorKey: null }));
      return;
    }
    uiStore.set((s) => ({ ...s, activeId: it.parentId!, selection: it.key ? [it.key] : [], anchorKey: it.key }));
  };

  const selectTrack = (axis: 'row' | 'col', index: number, shift: boolean) => {
    const g = active.node.grid;
    if (!g) return;
    const last = axis === 'row' ? g.cols.length - 1 : g.rows.length - 1;
    let lo = index;
    let hi = index;
    if (shift && ui.anchorKey && active.node.children[ui.anchorKey]) {
      const [r, c] = parseKey(ui.anchorKey);
      const a = axis === 'row' ? r : c;
      lo = Math.min(a, index);
      hi = Math.max(a, index);
    }
    const rg = axis === 'row' ? { r0: lo, r1: hi, c0: 0, c1: last } : { r0: 0, r1: last, c0: lo, c1: hi };
    const keys = keysInRange(g, rg);
    uiStore.set((s) => ({ ...s, selection: keys, anchorKey: shift ? s.anchorKey : axis === 'row' ? `${index},0` : `0,${index}` }));
  };

  /* ---- 指针事件 ---- */
  const startViewDrag = (s0: Pt) => {
    const v = viewStore.get();
    if (v.locked) {
      drag.current = null;
      return;
    }
    if (v.mode === 'rotate') {
      const pivot = { x: size.w / 2, y: size.h / 2 };
      drag.current = { type: 'rotate', a0: Math.atan2(s0.y - pivot.y, s0.x - pivot.x), rot0: v.rot, pan0: { x: v.panX, y: v.panY }, pivot };
    } else drag.current = { type: 'pan', s0, pan0: { x: v.panX, y: v.panY } };
  };

  const startPinch = () => {
    const [a, b] = [...pointers.current.values()];
    drag.current = {
      type: 'pinch',
      c0: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
      d0: Math.hypot(b.x - a.x, b.y - a.y) || 1,
      a0: Math.atan2(b.y - a.y, b.x - a.x),
      view0: viewStore.get(),
    };
  };

  const onPointerDown = (e: RPointerEvent<SVGSVGElement>) => {
    if (e.pointerType === 'mouse' && e.button > 1) return;
    svgRef.current!.setPointerCapture(e.pointerId);
    const s0 = local(e);
    pointers.current.set(e.pointerId, s0);
    if (pointers.current.size === 2) {
      if (drag.current && (drag.current.type === 'move' || drag.current.type === 'resize' || drag.current.type === 'track'))
        projectActions.dropCheckpointIfUnchanged();
      startPinch();
      return;
    }
    if (pointers.current.size > 2) return;
    const t = e.target as Element;
    if (e.button === 1) {
      drag.current = { type: 'pan', s0, pan0: { x: view.panX, y: view.panY } };
      return;
    }
    const handle = t.closest('[data-handle]');
    const track = t.closest('[data-track]');
    const seg = t.closest('[data-seg]');
    const nodeEl = t.closest('[data-id]');
    const f0 = toField(e);
    if (handle) {
      const id = handle.getAttribute('data-for')!;
      const it = layout.byId.get(id)!;
      const fit0 = it.node.fit ?? { x: 0, y: 0, w: it.slot.w, h: it.slot.h };
      projectActions.checkpoint();
      drag.current = { type: 'resize', id, dir: handle.getAttribute('data-handle')!, f0, fit0, slot: it.slot, start: project };
      return;
    }
    if (track) {
      const axis = track.getAttribute('data-axis') as 'row' | 'col';
      const index = Number(track.getAttribute('data-track'));
      const g = active.node.grid!;
      const scale = axis === 'col' ? active.w / sum(g.cols) : active.h / sum(g.rows);
      projectActions.checkpoint();
      drag.current = { type: 'track', cid: active.id, axis, index, f0, size0: (axis === 'col' ? g.cols : g.rows)[index], scale, start: project };
      return;
    }
    if (seg) {
      selectTrack(seg.getAttribute('data-axis') as 'row' | 'col', Number(seg.getAttribute('data-seg')), e.shiftKey);
      return;
    }
    const id = nodeEl?.getAttribute('data-id') ?? null;
    let movable = false;
    if (id) {
      const target = pickTarget(id);
      movable = !!target && !!target.node.fit && target.parentId === active.id;
    }
    drag.current = { type: 'pending', id, s0, shift: e.shiftKey, ctrl: e.ctrlKey || e.metaKey, movable, f0 };
  };

  const onPointerMove = (e: RPointerEvent<SVGSVGElement>) => {
    const s = local(e);
    if (pointers.current.has(e.pointerId)) pointers.current.set(e.pointerId, s);
    const d = drag.current;
    if (!d) {
      if (e.pointerType === 'mouse') {
        const el = (e.target as Element).closest('[data-id]');
        const id = el?.getAttribute('data-id') ?? null;
        const t = id ? pickTarget(id) : null;
        const hid = t?.id ?? null;
        if (hid !== hoverId) setHoverId(hid);
      }
      return;
    }
    if (d.type === 'pending') {
      if (Math.hypot(s.x - d.s0.x, s.y - d.s0.y) < 4) return;
      if (d.movable && d.id) {
        const target = pickTarget(d.id)!;
        select(d.id, false, false);
        projectActions.checkpoint();
        drag.current = { type: 'move', id: target.id, f0: d.f0, fit0: { ...target.node.fit! }, start: project };
      } else startViewDrag(d.s0);
      if (drag.current && drag.current.type !== 'pending') onPointerMove(e);
      return;
    }
    const v = viewStore.get();
    switch (d.type) {
      case 'pan':
        setView({ panX: d.pan0.x + s.x - d.s0.x, panY: d.pan0.y + s.y - d.s0.y });
        break;
      case 'rotate': {
        let da = (Math.atan2(s.y - d.pivot.y, s.x - d.pivot.x) - d.a0) / DEG;
        let rot = d.rot0 + da;
        if (e.shiftKey) {
          rot = Math.round(rot / 15) * 15;
          da = rot - d.rot0;
        }
        const p = rotPt({ x: d.pan0.x - d.pivot.x, y: d.pan0.y - d.pivot.y }, da);
        setView({ rot: normDeg(rot), panX: d.pivot.x + p.x, panY: d.pivot.y + p.y });
        break;
      }
      case 'pinch': {
        if (v.locked || pointers.current.size < 2) return;
        const [a, b] = [...pointers.current.values()];
        const c = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        const k = Math.hypot(b.x - a.x, b.y - a.y) / d.d0;
        const da = d.view0.mode === 'rotate' ? (Math.atan2(b.y - a.y, b.x - a.x) - d.a0) / DEG : 0;
        const z = clamp(d.view0.zoom * k, 0.5, 2000);
        const kk = z / d.view0.zoom;
        const p = rotPt({ x: (d.view0.panX - d.c0.x) * kk, y: (d.view0.panY - d.c0.y) * kk }, da);
        setView({ zoom: z, rot: normDeg(d.view0.rot + da), panX: c.x + p.x, panY: c.y + p.y });
        break;
      }
      case 'move': {
        const f = toField(e);
        const x = round(d.fit0.x + f.x - d.f0.x, 2);
        const y = round(d.fit0.y + f.y - d.f0.y, 2);
        projectActions.replace(produce(d.start, (dr) => moveNode(dr.field, d.id, x, y)));
        break;
      }
      case 'resize': {
        const f = toField(e);
        const dx = f.x - d.f0.x;
        const dy = f.y - d.f0.y;
        const s0 = d.fit0;
        let { x, y, w, h } = s0;
        if (d.dir.includes('e')) w = clamp(s0.w + dx, MIN_SIZE, d.slot.w - s0.x);
        if (d.dir.includes('w')) {
          x = clamp(s0.x + dx, 0, s0.x + s0.w - MIN_SIZE);
          w = s0.x + s0.w - x;
        }
        if (d.dir.includes('s')) h = clamp(s0.h + dy, MIN_SIZE, d.slot.h - s0.y);
        if (d.dir.includes('n')) {
          y = clamp(s0.y + dy, 0, s0.y + s0.h - MIN_SIZE);
          h = s0.y + s0.h - y;
        }
        [x, y, w, h] = [x, y, w, h].map((n) => round(n, 2));
        projectActions.replace(
          produce(d.start, (dr) => {
            const loc = locate(dr.field, d.id);
            if (!loc) return;
            if (loc.node.grid) scaleContent(loc.node, w / s0.w, h / s0.h);
            placeNode(dr.field, d.id, w, h, { x, y });
          }),
        );
        break;
      }
      case 'track': {
        const f = toField(e);
        const delta = (d.axis === 'col' ? f.x - d.f0.x : f.y - d.f0.y) / d.scale;
        const size = Math.max(MIN_SIZE, round(d.size0 + delta, 2));
        projectActions.replace(produce(d.start, (dr) => setTrackSize(dr.field, d.cid, d.axis, d.index, size)));
        break;
      }
    }
  };

  const onPointerUp = (e: RPointerEvent<SVGSVGElement>) => {
    pointers.current.delete(e.pointerId);
    const d = drag.current;
    if (d?.type === 'pinch') {
      if (pointers.current.size < 2) drag.current = null;
      return;
    }
    if (d?.type === 'pending') {
      // 自行识别双击/双触（指针捕获后浏览器的 dblclick 目标不可靠）
      const now = performance.now();
      const s = local(e);
      const last = lastTap.current;
      if (d.id && last && last.id === d.id && now - last.t < 400 && Math.hypot(s.x - last.x, s.y - last.y) < 12) {
        lastTap.current = null;
        enter(d.id);
      } else {
        lastTap.current = { t: now, id: d.id, x: s.x, y: s.y };
        select(d.id, d.shift, d.ctrl);
      }
    }
    if (d && (d.type === 'move' || d.type === 'resize' || d.type === 'track')) projectActions.dropCheckpointIfUnchanged();
    drag.current = null;
  };

  /* 滚轮缩放（非被动监听） */
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = svg.getBoundingClientRect();
      const at = { x: e.clientX - r.left, y: e.clientY - r.top };
      if (e.shiftKey) viewOps.rotateBy(e.deltaY > 0 ? 5 : -5, { x: r.width / 2, y: r.height / 2 });
      else viewOps.zoomBy(Math.exp(-e.deltaY * 0.0015), at);
    };
    svg.addEventListener('wheel', onWheel, { passive: false });
    return () => svg.removeEventListener('wheel', onWheel);
  }, []);

  /* 键盘 */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      const k = e.key.toLowerCase();
      const center = { x: size.w / 2, y: size.h / 2 };
      if ((e.ctrlKey || e.metaKey) && k === 'z') {
        e.preventDefault();
        if (e.shiftKey) projectActions.redo();
        else projectActions.undo();
      } else if ((e.ctrlKey || e.metaKey) && k === 'y') {
        e.preventDefault();
        projectActions.redo();
      } else if (e.ctrlKey || e.metaKey || e.altKey) return;
      else if (k === 'escape') goUp();
      else if (k === 'enter' && ui.selection.length === 1) {
        const ch = active.node.children[ui.selection[0]];
        if (ch?.grid) uiStore.set((s) => ({ ...s, activeId: ch.id, selection: [], anchorKey: null }));
      } else if (k === 'q') viewOps.rotateBy(-15, center);
      else if (k === 'e') viewOps.rotateBy(15, center);
      else if (k === 'r') setView({ mode: viewStore.get().mode === 'rotate' ? 'pan' : 'rotate' });
      else if (k === 'l') setView({ locked: !viewStore.get().locked });
      else if (k === 'f') fit();
      else if (k === '+' || k === '=') viewOps.zoomBy(1.2, center);
      else if (k === '-') viewOps.zoomBy(1 / 1.2, center);
      else if (k.startsWith('arrow') && ui.selection.length === 1) {
        const ch = active.node.children[ui.selection[0]];
        if (!ch?.fit) return;
        e.preventDefault();
        const step = e.shiftKey ? 1 : 0.1;
        const dx = k === 'arrowleft' ? -step : k === 'arrowright' ? step : 0;
        const dy = k === 'arrowup' ? -step : k === 'arrowdown' ? step : 0;
        projectActions.update((d) => moveNode(d.field, ch.id, round(ch.fit!.x + dx, 2), round(ch.fit!.y + dy, 2)), {
          coalesce: `nudge:${ch.id}`,
        });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  /* ---- 渲染 ---- */
  const z = view.zoom;
  const px = (n: number) => n / z;
  const rot = view.rot;
  const transform = `translate(${view.panX} ${view.panY}) rotate(${rot}) scale(${z})`;
  const cosr = Math.cos(rot * DEG) ** 2;
  const sinr = Math.sin(rot * DEG) ** 2;
  const rmod = ((rot % 90) + 90) % 90;
  const axisAligned = rmod < 2 || rmod > 88;
  const activePath = new Set(pathIds(layout, active.id));
  const selectedItems = ui.selection.map((k) => active.node.children[k]).filter(Boolean).map((n) => layout.byId.get(n.id)!);

  const shapes: JSX.Element[] = [];
  const labels: JSX.Element[] = [];
  for (const it of layout.items) {
    const n = it.node;
    const inActive = it.depth > activeDepth && isDesc(layout, it, active.id);
    const dim = activeDepth > 0 && !activePath.has(it.id) && !inActive;
    if (it.depth > 0 && n.fit) {
      shapes.push(
        <rect
          key={`slot-${it.id}`}
          data-id={it.id}
          x={it.slot.x}
          y={it.slot.y}
          width={it.slot.w}
          height={it.slot.h}
          fill="url(#pat-road)"
          className="slot-road"
        />,
      );
    }
    shapes.push(
      <rect
        key={it.id}
        data-id={it.id}
        x={it.x}
        y={it.y}
        width={Math.max(0, it.w)}
        height={Math.max(0, it.h)}
        fill={fillOf(it)}
        className={`cell d${Math.min(it.depth, 3)} k-${n.kind}${dim ? ' dim' : ''}${hoverId === it.id ? ' hover' : ''}`}
      />,
    );

    // 标签
    if (it.depth === 0) continue;
    const hasKids = !!n.grid && Object.keys(n.children).length > 0;
    const cx = it.x + it.w / 2;
    const cy = it.y + it.h / 2;
    if (!hasKids) {
      const lines = labelLines(project, it, vmap);
      if (!lines.length) continue;
      const maxEm = Math.max(...lines.map(emWidth), 1);
      const fitFs = (W: number, H: number) => Math.min((H * 0.82) / (lines.length * 1.25), (W * 0.88) / maxEm, px(15));
      let fs: number;
      let angle: number;
      if (axisAligned) {
        // 视角为 90° 的倍数：文字保持正立
        fs = fitFs(cosr * it.w + sinr * it.h, sinr * it.w + cosr * it.h);
        angle = -rot;
      } else {
        // 任意角度：文字沿单元方向排列（自动避免倒置）
        const horiz = (base: number) => (Math.abs(normDeg(base + rot)) <= 45 || Math.abs(normDeg(base + rot)) >= 135 ? 1.12 : 1);
        const f0 = fitFs(it.w, it.h) * horiz(0);
        const f1 = fitFs(it.h, it.w) * horiz(-90);
        const base = f0 >= f1 ? 0 : -90;
        fs = base === 0 ? fitFs(it.w, it.h) : fitFs(it.h, it.w);
        angle = readable(base, rot);
      }
      if (fs * z < 6.5) continue;
      labels.push(
        <g key={`l-${it.id}`} transform={`rotate(${angle} ${cx} ${cy})`} className={`lbl${dim ? ' dim' : ''}${n.kind !== 'normal' ? ' muted' : ''}`}>
          {lines.map((t, i) => (
            <text key={i} x={cx} y={cy + (i - (lines.length - 1) / 2) * fs * 1.25} fontSize={fs} fontWeight={i === 0 ? 600 : 400}>
              {t}
            </text>
          ))}
        </g>,
      );
    } else if (it.depth === 1 && it.id !== active.id) {
      const title = nodeTitle(n, it.depth);
      const fs = px(11);
      const tw = emWidth(title) * fs + px(10);
      if (it.w * z < 40) continue;
      labels.push(
        <g key={`t-${it.id}`} transform={`rotate(${-rot} ${it.x} ${it.y})`} className={`tag${dim ? ' dim' : ''}`} pointerEvents="none">
          <rect x={it.x} y={it.y - px(18)} width={tw} height={px(16)} rx={px(3)} />
          <text x={it.x + px(5)} y={it.y - px(10)} fontSize={fs}>
            {title}
          </text>
        </g>,
      );
    }
  }

  // 选中框与把手
  const overlay: JSX.Element[] = [];
  overlay.push(
    <rect key="active" x={active.x} y={active.y} width={active.w} height={active.h} className="active-outline" pointerEvents="none" />,
  );
  for (const it of selectedItems) {
    if (it.node.fit) overlay.push(<rect key={`ss-${it.id}`} x={it.slot.x} y={it.slot.y} width={it.slot.w} height={it.slot.h} className="slot-outline" pointerEvents="none" />);
    overlay.push(<rect key={`s-${it.id}`} x={it.x} y={it.y} width={it.w} height={it.h} className="sel-outline" pointerEvents="none" />);
  }
  if (selectedItems.length === 1 && !view.locked) {
    const it = selectedItems[0];
    const hs = px(10);
    const pts: [string, number, number][] = [
      ['nw', it.x, it.y],
      ['n', it.x + it.w / 2, it.y],
      ['ne', it.x + it.w, it.y],
      ['e', it.x + it.w, it.y + it.h / 2],
      ['se', it.x + it.w, it.y + it.h],
      ['s', it.x + it.w / 2, it.y + it.h],
      ['sw', it.x, it.y + it.h],
      ['w', it.x, it.y + it.h / 2],
    ];
    for (const [dir, x, y] of pts)
      overlay.push(<rect key={`h-${dir}`} data-handle={dir} data-for={it.id} x={x - hs / 2} y={y - hs / 2} width={hs} height={hs} className="handle" />);
  }
  if (selectedItems.length === 1 && view.locked) {
    // 锁定视角时仍允许编辑尺寸
    const it = selectedItems[0];
    const hs = px(10);
    for (const [dir, x, y] of [
      ['se', it.x + it.w, it.y + it.h],
      ['nw', it.x, it.y],
      ['ne', it.x + it.w, it.y],
      ['sw', it.x, it.y + it.h],
    ] as [string, number, number][])
      overlay.push(<rect key={`h-${dir}`} data-handle={dir} data-for={it.id} x={x - hs / 2} y={y - hs / 2} width={hs} height={hs} className="handle" />);
  }

  // 标尺（当前容器的行/列）
  const rulers: JSX.Element[] = [];
  if (active.node.grid) {
    const g = active.node.grid;
    const sx = active.w / sum(g.cols);
    const sy = active.h / sum(g.rows);
    const band = px(20);
    const gap = px(4);
    const fs = px(10);
    const selCols = new Set<number>();
    const selRows = new Set<number>();
    for (const k of ui.selection) {
      const [r, c] = parseKey(k);
      const m = g.merges.find((x) => x.r === r && x.c === c) ?? { r, c, rs: 1, cs: 1 };
      for (let i = m.c; i < m.c + m.cs; i++) selCols.add(i);
      for (let i = m.r; i < m.r + m.rs; i++) selRows.add(i);
    }
    let acc = 0;
    g.cols.forEach((w, i) => {
      const x = active.x + acc * sx;
      const ww = w * sx;
      acc += w;
      const by = active.y - gap - band;
      rulers.push(
        <g key={`cs${i}`} data-seg={i} data-axis="col" className={`seg${selCols.has(i) ? ' on' : ''}`}>
          <rect x={x} y={by} width={ww} height={band} />
          {ww * z > 26 && (
            <text x={x + ww / 2} y={by + band / 2} fontSize={fs} transform={`rotate(${readable(0, rot)} ${x + ww / 2} ${by + band / 2})`}>
              {ww * z > 64 ? `${i + 1} · ${fmtM(w)}` : fmtM(w)}
            </text>
          )}
        </g>,
      );
      rulers.push(
        <rect key={`ct${i}`} data-track={i} data-axis="col" className="track-handle col" x={x + ww - px(4)} y={by} width={px(8)} height={band} />,
      );
    });
    acc = 0;
    g.rows.forEach((h, i) => {
      const y = active.y + acc * sy;
      const hh = h * sy;
      acc += h;
      const bx = active.x - gap - band;
      rulers.push(
        <g key={`rs${i}`} data-seg={i} data-axis="row" className={`seg${selRows.has(i) ? ' on' : ''}`}>
          <rect x={bx} y={y} width={band} height={hh} />
          {hh * z > 26 && (
            <text x={bx + band / 2} y={y + hh / 2} fontSize={fs} transform={`rotate(${readable(-90, rot)} ${bx + band / 2} ${y + hh / 2})`}>
              {hh * z > 64 ? `${i + 1} · ${fmtM(h)}` : fmtM(h)}
            </text>
          )}
        </g>,
      );
      rulers.push(
        <rect key={`rt${i}`} data-track={i} data-axis="row" className="track-handle row" x={bx} y={y + hh - px(4)} width={band} height={px(8)} />,
      );
    });
  }

  // 方位标注（东南西北）
  const dirs: JSX.Element[] = [];
  {
    const cx = layout.width / 2;
    const cy = layout.height / 2;
    const hw = layout.width / 2;
    const hh = layout.height / 2;
    const r = px(13);
    const margin = px(38);
    (['北', '东', '南', '西'] as const).forEach((label, i) => {
      const ang = (project.northDeg + i * 90) * DEG;
      const dx = Math.sin(ang);
      const dy = -Math.cos(ang);
      const t = Math.min(Math.abs(dx) > 1e-6 ? hw / Math.abs(dx) : Infinity, Math.abs(dy) > 1e-6 ? hh / Math.abs(dy) : Infinity);
      const x = cx + dx * (t + margin);
      const y = cy + dy * (t + margin);
      dirs.push(
        <g key={label} className={`dir${i === 0 ? ' north' : ''}`} transform={`rotate(${-rot} ${x} ${y})`} pointerEvents="none">
          <circle cx={x} cy={y} r={r} />
          <text x={x} y={y} fontSize={px(14)}>
            {label}
          </text>
        </g>,
      );
    });
  }

  const crumbs = pathIds(layout, active.id).map((id) => layout.byId.get(id)!);
  const nf = numericFields(project);

  return (
    <div className="map-wrap" ref={wrapRef}>
      <svg
        ref={svgRef}
        className={`map-svg ${view.locked ? 'locked' : view.mode}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onPointerLeave={() => setHoverId(null)}
        onContextMenu={(e) => e.preventDefault()}
      >
        <defs>
          <pattern id="pat-road" patternUnits="userSpaceOnUse" width={0.5} height={0.5} patternTransform="rotate(45)">
            <rect width={0.5} height={0.5} fill="var(--map-road)" />
            <line x1={0} y1={0} x2={0} y2={0.5} stroke="var(--map-road-line)" strokeWidth={0.08} />
          </pattern>
          <pattern id="pat-protect" patternUnits="userSpaceOnUse" width={0.6} height={0.6} patternTransform="rotate(-45)">
            <rect width={0.6} height={0.6} fill="var(--map-protect)" />
            <line x1={0} y1={0} x2={0} y2={0.6} stroke="var(--map-protect-line)" strokeWidth={0.1} />
          </pattern>
        </defs>
        {view.ready && (
          <g ref={gRef} transform={transform}>
            {shapes}
            {labels}
            {dirs}
            {rulers}
            {overlay}
          </g>
        )}
        {!view.ready && <g ref={gRef} />}
      </svg>

      <div className="map-top">
        <nav className="crumbs" aria-label="层级">
          {crumbs.map((c, i) => (
            <span key={c.id}>
              {i > 0 && <span className="sep">›</span>}
              <button
                className={c.id === active.id ? 'cur' : ''}
                onClick={() => uiStore.set((s) => ({ ...s, activeId: c.id, selection: [], anchorKey: null }))}
              >
                {nodeTitle(c.node, c.depth)}
              </button>
            </span>
          ))}
          {active.parentId && (
            <button className="up" onClick={goUp} title="返回上一级 (Esc)">
              <Icon name="up" size={14} />
            </button>
          )}
        </nav>
        <div className="toolbar">
          <div className="seg-group" role="group" aria-label="拖动模式">
            <button className={view.mode === 'pan' ? 'on' : ''} onClick={() => setView({ mode: 'pan' })} title="拖动背景平移视角" aria-label="平移">
              <Icon name="hand" /> <span className="tb-label">平移</span>
            </button>
            <button className={view.mode === 'rotate' ? 'on' : ''} onClick={() => setView({ mode: 'rotate' })} title="拖动背景自由旋转视角 (R)，按住Shift以15°吸附" aria-label="旋转">
              <Icon name="rotate" /> <span className="tb-label">旋转</span>
            </button>
          </div>
          <button className={`lock${view.locked ? ' on' : ''}`} onClick={() => setView({ locked: !view.locked })} title="锁定/解锁视角 (L)" aria-label={view.locked ? '已锁定' : '锁定视角'}>
            <Icon name={view.locked ? 'lock' : 'unlock'} /> <span className="tb-label">{view.locked ? '已锁定' : '锁定视角'}</span>
          </button>
          <span className="tb-sep" />
          <button disabled={view.locked} onClick={() => viewOps.rotateBy(-90, { x: size.w / 2, y: size.h / 2 })} title="逆时针90°" aria-label="逆时针90°">
            <Icon name="rotL" />
          </button>
          <button disabled={view.locked} onClick={() => viewOps.rotateBy(90, { x: size.w / 2, y: size.h / 2 })} title="顺时针90°" aria-label="顺时针90°">
            <Icon name="rotate" />
          </button>
          <button disabled={view.locked} onClick={() => fit(normDeg(-project.northDeg))} title="正北朝上" aria-label="正北朝上">
            <Icon name="north" /> 北
          </button>
          <button disabled={view.locked} onClick={() => fit(0)} title="图纸朝上" aria-label="图纸朝上">
            0°
          </button>
          <button disabled={view.locked} onClick={() => fit()} title="适应窗口 (F)" aria-label="适应窗口">
            <Icon name="fit" />
          </button>
          <button disabled={view.locked} onClick={() => viewOps.zoomBy(1.25, { x: size.w / 2, y: size.h / 2 })} title="放大" aria-label="放大">
            <Icon name="plus" />
          </button>
          <button disabled={view.locked} onClick={() => viewOps.zoomBy(0.8, { x: size.w / 2, y: size.h / 2 })} title="缩小" aria-label="缩小">
            <Icon name="minus" />
          </button>
          <span className="tb-sep" />
          <label className="color-by">
            <span className="tb-label">着色</span>
            <select value={ui.colorBy} onChange={(e) => uiStore.set((s) => ({ ...s, colorBy: e.target.value }))}>
              <option value="variety">品种</option>
              <option value="rep">重复</option>
              <option value="none">无</option>
              <optgroup label="按字段数值">
                {nf.map((f) => (
                  <option key={f.key} value={f.key}>
                    {f.label}
                  </option>
                ))}
              </optgroup>
            </select>
          </label>
        </div>
      </div>

      <Compass
        angle={project.northDeg + rot}
        rot={rot}
        locked={view.locked}
        onClick={() => fit(normDeg(-project.northDeg))}
      />
      <ScaleBar zoom={z} />
      {heatRange && (
        <div className="legend">
          <span>{nf.find((f) => f.key === ui.colorBy)?.label}</span>
          <span>{heatRange.n ? fmtNum(heatRange.min) : '无数据'}</span>
          <span className="grad" />
          <span>{heatRange.n ? fmtNum(heatRange.max) : ''}</span>
        </div>
      )}
      <div className="map-hint">
        {view.locked
          ? '视角已锁定 · 单击选择 · 双击进入 · Esc 返回上级'
          : `${view.mode === 'rotate' ? '拖动旋转' : '拖动平移'} · 滚轮缩放 · Shift+滚轮旋转 · 单击选择 · Shift/Ctrl多选 · 双击进入 · 拖动选中块的把手调整大小`}
      </div>
    </div>
  );
}

/** 沿标尺方向的文字角度，保证屏幕上不倒置 */
function readable(base: number, rot: number) {
  const a = normDeg(base + rot);
  return a > 90 || a < -90 ? base + 180 : base;
}

function p0(v: number) {
  return typeof v === 'number' && isFinite(v);
}

function fmtM(v: number) {
  return `${Math.round(v * 100) / 100}m`;
}
function fmtNum(v: number) {
  return String(Math.round(v * 100) / 100);
}

function isDesc(layout: Layout, it: LayoutItem, ancestorId: string): boolean {
  let cur: LayoutItem | undefined = it;
  while (cur) {
    if (cur.id === ancestorId) return true;
    cur = cur.parentId ? layout.byId.get(cur.parentId) : undefined;
  }
  return false;
}

function labelLines(p: Project, it: LayoutItem, vmap: Map<string, import('../model/types').Variety>): string[] {
  const n = it.node;
  if (n.kind !== 'normal') return [nodeTitle(n, it.depth)];
  const level = levelOfDepth(it.depth);
  const defs = p.schemas[level].filter((f) => f.onMap);
  const out = defs.map((f) => displayValue(f, n.data[f.key], vmap)).filter((s) => s);
  if (!out.length && it.depth === 1) return [nodeTitle(n, 1)];
  return out;
}
