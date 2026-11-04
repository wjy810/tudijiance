import { DEFAULT_SCHEMAS } from './fields';
import { anchors, keyOf, makeGrid, newNode, normalizeChildren, uid } from './grid';
import type { FieldDef, Merge, PNode, Project, SchemaLevel, Variety } from './types';

export const ROMAN = ['Ⅰ', 'Ⅱ', 'Ⅲ', 'Ⅳ', 'Ⅴ', 'Ⅵ', 'Ⅶ', 'Ⅷ', 'Ⅸ', 'Ⅹ'];
export const roman = (n: number) => ROMAN[n - 1] ?? String(n);

export interface GenOptions {
  reps: number;
  repRows: number;
  repCols: number;
  plotW: number;
  plotL: number;
  protect: number;
  alley: number;
  /** h：重复左右并排；v：重复上下排列 */
  direction: 'h' | 'v';
  numberBase: number;
  assign: 'seq' | 'random' | 'none';
}

export const DEFAULT_GEN: GenOptions = {
  reps: 3,
  repRows: 1,
  repCols: 12,
  plotW: 2,
  plotL: 6.67,
  protect: 2,
  alley: 1,
  direction: 'v',
  numberBase: 100,
  assign: 'random',
};

function shuffle<T>(a: T[]): T[] {
  const b = [...a];
  for (let i = b.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [b[i], b[j]] = [b[j], b[i]];
  }
  return b;
}

export type NumberOrder = 'row' | 'col' | 'snake';

/** 区块内小区排序 */
export function orderedKeys(block: PNode, order: NumberOrder): string[] {
  if (!block.grid) return [];
  const as = anchors(block.grid).filter((a) => block.children[keyOf(a.r, a.c)]?.kind === 'normal');
  if (order === 'col') as.sort((a, b) => a.c - b.c || a.r - b.r);
  else if (order === 'snake') as.sort((a, b) => a.r - b.r || (a.r % 2 ? b.c - a.c : a.c - b.c));
  else as.sort((a, b) => a.r - b.r || a.c - b.c);
  return as.map((a) => keyOf(a.r, a.c));
}

/** 批量编号 */
export function numberPlots(block: PNode, start: number, order: NumberOrder): void {
  orderedKeys(block, order).forEach((k, i) => {
    block.children[k].data.plotNo = String(start + i);
  });
}

/** 批量分配品种 */
export function assignVarieties(block: PNode, varieties: Variety[], mode: 'seq' | 'random', order: NumberOrder): void {
  if (!varieties.length) return;
  const keys = orderedKeys(block, order);
  let pool: Variety[] = [];
  keys.forEach((k, i) => {
    if (i % varieties.length === 0) pool = mode === 'random' ? shuffle(varieties) : [...varieties];
    block.children[k].data.variety = pool[i % varieties.length].id;
  });
}

export function generateField(o: GenOptions, varieties: Variety[]): PNode {
  const W = o.repCols * o.plotW;
  const H = o.repRows * o.plotL;
  const main: number[] = [];
  const repIdx: number[] = [];
  for (let i = 0; i < o.reps; i++) {
    if (i > 0 && o.alley > 0) {
      main.push(o.alley);
      repIdx.push(-1);
    }
    main.push(o.direction === 'h' ? W : H);
    repIdx.push(i);
  }
  const cross = o.direction === 'h' ? H : W;
  const P = o.protect > 0 ? [o.protect] : [];
  const along = [...P, ...main, ...P];
  const across = [...P, cross, ...P];
  const rows = o.direction === 'h' ? across : along;
  const cols = o.direction === 'h' ? along : across;
  const merges: Merge[] = [];
  const p = P.length;
  if (p) {
    const nr = rows.length;
    const nc = cols.length;
    merges.push({ r: 0, c: 0, rs: 1, cs: nc });
    merges.push({ r: nr - 1, c: 0, rs: 1, cs: nc });
    merges.push({ r: 1, c: 0, rs: nr - 2, cs: 1 });
    merges.push({ r: 1, c: nc - 1, rs: nr - 2, cs: 1 });
  }
  const root = newNode('normal', { name: '试验田' });
  root.grid = makeGrid(rows, cols, merges.filter((m) => m.rs * m.cs > 1));
  normalizeChildren(root);
  const nr = rows.length;
  const nc = cols.length;
  for (const a of anchors(root.grid)) {
    const k = keyOf(a.r, a.c);
    const ch = root.children[k];
    const isProtect = p > 0 && (a.r === 0 || a.c === 0 || a.r === nr - 1 || a.c === nc - 1);
    if (isProtect) {
      ch.kind = 'protect';
      ch.data = { name: '保护区' };
      continue;
    }
    const mi = (o.direction === 'h' ? a.c : a.r) - p;
    const ri = repIdx[mi];
    if (ri === undefined || ri < 0) {
      ch.kind = 'road';
      ch.data = { name: '走道' };
      continue;
    }
    const rep = ri + 1;
    ch.data = { name: `重复${roman(rep)}`, rep: String(rep) };
    ch.grid = makeGrid(
      Array.from({ length: o.repRows }, () => o.plotL),
      Array.from({ length: o.repCols }, () => o.plotW),
    );
    normalizeChildren(ch);
    numberPlots(ch, rep * o.numberBase + 1, 'row');
    if (o.assign !== 'none') assignVarieties(ch, varieties, o.assign, 'row');
  }
  return root;
}

export function sampleVarieties(n = 12): Variety[] {
  return Array.from({ length: n }, (_, i) => ({
    id: uid('v'),
    code: String(i + 1),
    name: i === 0 ? '对照品种' : `品种${String(i + 1).padStart(2, '0')}`,
    role: i === 0 ? 'ck' : 'test',
  }));
}

export function cloneSchemas(): Record<SchemaLevel, FieldDef[]> {
  return JSON.parse(JSON.stringify(DEFAULT_SCHEMAS));
}

export function createProject(): Project {
  const varieties = sampleVarieties(12);
  const field = generateField(DEFAULT_GEN, varieties);
  return {
    version: 1,
    name: '2024-2025 河南省小麦品种试验',
    meta: {
      year: '2024-2025',
      commonCK: varieties[0].id,
      reps: '3',
      plotLength: '6.67',
      plotWidth: '2',
      prevCrop: '玉米',
      soil: '壤土',
      irrigated: '水地',
    },
    schemas: cloneSchemas(),
    varieties,
    weather: {},
    weatherAnalysis: '',
    northDeg: 0,
    field,
    updatedAt: new Date().toISOString(),
  };
}

/** 兼容旧数据：补齐缺失的内置字段 */
export function migrateProject(p: Project): Project {
  const schemas = { ...cloneSchemas(), ...(p.schemas ?? {}) } as Record<SchemaLevel, FieldDef[]>;
  for (const lvl of Object.keys(DEFAULT_SCHEMAS) as SchemaLevel[]) {
    const have = new Set(schemas[lvl].map((f) => f.key));
    for (const f of DEFAULT_SCHEMAS[lvl]) if (f.system && !have.has(f.key)) schemas[lvl].push({ ...f });
  }
  return {
    ...createProject(),
    ...p,
    schemas,
    meta: p.meta ?? {},
    weather: p.weather ?? {},
    varieties: p.varieties ?? [],
    northDeg: Number(p.northDeg) || 0,
  };
}
