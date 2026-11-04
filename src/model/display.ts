import { optionLabel, parseOption } from './fields';
import type { FieldDef, PNode, Project, Variety } from './types';

export function displayValue(def: FieldDef, value: string | undefined, vmap: Map<string, Variety>): string {
  if (value == null || value === '') return '';
  if (def.type === 'variety') return vmap.get(value)?.name ?? '';
  if (def.type === 'select') {
    const o = def.options?.map(parseOption).find((x) => x.value === value);
    return o ? o.label : value;
  }
  return value;
}

export { optionLabel };

export function nodeTitle(node: PNode, depth: number): string {
  if (depth === 0) return node.data.name || '试验田';
  if (node.kind === 'road') return node.data.name || '路面';
  if (node.kind === 'protect') return node.data.name || '保护区';
  if (node.kind === 'blank') return node.data.name || '空地';
  if (depth === 1) return node.data.name || '区块';
  if (depth === 2) return node.data.plotNo ? `小区 ${node.data.plotNo}` : '小区';
  return node.data.name || '子块';
}

export const KIND_LABELS: Record<PNode['kind'], string> = {
  normal: '种植区',
  road: '路面',
  protect: '保护区',
  blank: '空地',
};

/* ---------- 颜色 ---------- */

export function varietyColor(index: number, role?: Variety['role']): string {
  if (role === 'ck') return 'hsl(42 92% 70%)';
  if (role === 'ck2') return 'hsl(28 90% 76%)';
  const hue = (index * 47 + 95) % 360;
  return `hsl(${hue} 52% 80%)`;
}

const REP_COLORS = ['#cfe8c4', '#c9def5', '#f5dcc4', '#e3d1f2', '#f3cfd8', '#d1efe9'];
export const repColor = (rep: number) => REP_COLORS[(rep - 1 + REP_COLORS.length) % REP_COLORS.length];

/** 黄 → 绿 色阶 */
export function heatColor(t: number): string {
  const tt = Math.min(1, Math.max(0, t));
  const h = 55 + tt * 75;
  const l = 88 - tt * 40;
  return `hsl(${h} 70% ${l}%)`;
}

export function numericFields(p: Project): FieldDef[] {
  return p.schemas.plot.filter((f) => f.type === 'number' || (f.type === 'select' && f.options?.every((o) => isFinite(Number(parseOption(o).value)))));
}
