import { walk } from './grid';
import type { FieldDef, PNode, Project, Variety } from './types';

export interface PlotRow {
  node: PNode;
  block: PNode;
  rep: number;
  blockName: string;
}

const num = (v: string | undefined): number | null => {
  if (v == null || v === '') return null;
  const n = Number(v);
  return isFinite(n) ? n : null;
};

/** 所有小区（第2层、类型为正常种植） */
export function collectPlots(p: Project): PlotRow[] {
  const out: PlotRow[] = [];
  let blockIdx = 0;
  walk(p.field, (n, depth, path) => {
    if (depth === 1 && n.kind === 'normal') blockIdx++;
    if (depth !== 2 || n.kind !== 'normal') return;
    const block = path[1];
    if (block.kind !== 'normal') return;
    out.push({ node: n, block, rep: num(block.data.rep) ?? blockIdx, blockName: block.data.name || '' });
  });
  out.sort((a, b) => a.rep - b.rep || plotNoCmp(a.node.data.plotNo, b.node.data.plotNo));
  return out;
}

export function plotNoCmp(a = '', b = ''): number {
  const na = Number(a);
  const nb = Number(b);
  if (isFinite(na) && isFinite(nb) && a !== '' && b !== '') return na - nb;
  return a.localeCompare(b, 'zh');
}

export function repList(p: Project, plots: PlotRow[]): number[] {
  const n = Math.max(num(p.meta.reps) ?? 0, ...plots.map((x) => x.rep), 0);
  return Array.from({ length: n }, (_, i) => i + 1);
}

/** 收获面积 m²：手动值优先；否则 收获长 × [(行数-1)×行距 + 畦埂宽]；再否则 小区长 × 小区宽 */
export function harvestArea(meta: Record<string, string>): number | null {
  const manual = num(meta.harvestArea);
  if (manual && manual > 0) return manual;
  const L = num(meta.harvestLength) ?? num(meta.plotLength);
  const rows = num(meta.rowsPerPlot);
  const sp = num(meta.rowSpacing);
  const ridge = num(meta.ridgeWidth) ?? 0;
  if (L && rows && sp) return L * (((rows - 1) * sp + ridge) / 100);
  const W = num(meta.plotWidth);
  if (L && W) return L * W;
  return null;
}

export const MU = 666.67;

export interface YieldRow {
  variety: Variety;
  plot: (number | null)[];
  plotMean: number | null;
  mu: (number | null)[];
  muMean: number | null;
  diff: number | null;
  rank: number | null;
}

const mean = (a: (number | null)[]) => {
  const v = a.filter((x): x is number => x != null);
  return v.length ? v.reduce((s, x) => s + x, 0) / v.length : null;
};

export function yieldTable(p: Project, base: 'mean' | 'ck'): { rows: YieldRow[]; reps: number[]; area: number | null; baseValue: number | null } {
  const plots = collectPlots(p);
  const reps = repList(p, plots);
  const area = harvestArea(p.meta);
  const rows: YieldRow[] = p.varieties.map((v) => {
    const plot = reps.map((r) => {
      const vals = plots.filter((x) => x.rep === r && x.node.data.variety === v.id).map((x) => num(x.node.data.plotYield));
      return mean(vals);
    });
    const mu = plot.map((y) => (y != null && area ? (y / area) * MU : null));
    return { variety: v, plot, plotMean: mean(plot), mu, muMean: mean(mu), diff: null, rank: null };
  });
  const valid = rows.filter((r) => r.muMean != null);
  let baseValue: number | null = null;
  if (base === 'ck') {
    const ck = rows.find((r) => r.variety.id === p.meta.commonCK) ?? rows.find((r) => r.variety.role === 'ck');
    baseValue = ck?.muMean ?? null;
  } else baseValue = mean(valid.map((r) => r.muMean));
  for (const r of rows) r.diff = r.muMean != null && baseValue ? ((r.muMean - baseValue) / baseValue) * 100 : null;
  [...valid].sort((a, b) => b.muMean! - a.muMean!).forEach((r, i) => (r.rank = i + 1));
  return { rows, reps, area, baseValue };
}

/** 日期 → 当年第几天（跨年按播种年度处理） */
const toDate = (s: string | undefined) => {
  if (!s) return null;
  const d = new Date(s);
  return isNaN(d.getTime()) ? null : d;
};

function mode(values: string[]): string {
  const cnt = new Map<string, number>();
  for (const v of values) cnt.set(v, (cnt.get(v) ?? 0) + 1);
  let best = '';
  let n = 0;
  for (const [v, c] of cnt) if (c > n) [best, n] = [v, c];
  return best;
}

export function summarizeField(def: FieldDef, values: string[]): string {
  const vs = values.filter((v) => v !== '' && v != null);
  if (!vs.length) return '';
  if (def.type === 'number') {
    const m = mean(vs.map((v) => num(v)));
    return m == null ? '' : String(Math.round(m * 100) / 100);
  }
  if (def.type === 'date') {
    const ds = vs.map(toDate).filter((d): d is Date => !!d);
    if (!ds.length) return mode(vs);
    const avg = new Date(ds.reduce((s, d) => s + d.getTime(), 0) / ds.length);
    return `${avg.getMonth() + 1}/${avg.getDate()}`;
  }
  return mode(vs);
}

export interface TraitRow {
  variety: Variety;
  values: Record<string, string>;
  growthDays: string;
  spikeRate: string;
}

/** 性状汇总：按品种对各重复取平均（数值）、众数（文本/等级）、平均日期 */
export function traitSummary(p: Project, fields: FieldDef[]): TraitRow[] {
  const plots = collectPlots(p);
  const sow = toDate(p.meta.sowingDate);
  return p.varieties.map((v) => {
    const mine = plots.filter((x) => x.node.data.variety === v.id);
    const values: Record<string, string> = {};
    for (const f of fields) values[f.key] = summarizeField(f, mine.map((x) => x.node.data[f.key] ?? ''));
    const mats = mine.map((x) => toDate(x.node.data.maturity)).filter((d): d is Date => !!d);
    const growthDays =
      sow && mats.length ? String(Math.round(mats.reduce((s, d) => s + (d.getTime() - sow.getTime()) / 86400000, 0) / mats.length)) : '';
    const spikes = num(values.spikes);
    const tillers = num(values.maxTillers);
    const spikeRate = spikes != null && tillers ? String(Math.round((spikes / tillers) * 1000) / 10) : '';
    return { variety: v, values, growthDays, spikeRate };
  });
}

export function varietyById(p: Project): Map<string, Variety> {
  return new Map(p.varieties.map((v) => [v.id, v]));
}

export const fmt = (v: number | null | undefined, d = 2) => (v == null || !isFinite(v) ? '' : String(Math.round(v * 10 ** d) / 10 ** d));
