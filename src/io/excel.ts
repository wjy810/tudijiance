import type { WorkBook, WorkSheet } from 'xlsx';
import { collectPlots, fmt, harvestArea, repList, summarizeField, traitSummary, yieldTable } from '../model/calc';
import { generateField, roman } from '../model/defaults';
import { displayValue, KIND_LABELS, nodeTitle } from '../model/display';
import { WEATHER_MONTHS, WEATHER_ROWS } from '../model/fields';
import { anchors, keyOf, uid, walk } from '../model/grid';
import type { FieldDef, PNode, Project, Variety } from '../model/types';

type XLSXMod = typeof import('xlsx');
type Cell = string | number;
type Merge = { s: { r: number; c: number }; e: { r: number; c: number } };

const loadXLSX = () => import('xlsx') as Promise<XLSXMod>;

function sheet(X: XLSXMod, rows: Cell[][], merges: Merge[] = [], widths?: number[]): WorkSheet {
  const ws = X.utils.aoa_to_sheet(rows);
  if (merges.length) ws['!merges'] = merges;
  if (widths) ws['!cols'] = widths.map((w) => ({ wch: w }));
  return ws;
}

const mg = (r: number, c: number, rs = 1, cs = 1): Merge => ({ s: { r, c }, e: { r: r + rs - 1, c: c + cs - 1 } });

export async function exportExcel(p: Project): Promise<void> {
  const X = await loadXLSX();
  const wb: WorkBook = X.utils.book_new();
  const vmap = new Map(p.varieties.map((v) => [v.id, v]));
  const plots = collectPlots(p);
  const reps = repList(p, plots);
  const vName = (id: string | undefined) => (id ? vmap.get(id)?.name ?? '' : '');

  /* 基本情况 */
  {
    const rows: Cell[][] = [[p.name || `${p.meta.year || ''} 小麦品种试验记载本`], []];
    const merges: Merge[] = [mg(0, 0, 1, 3)];
    let g = '';
    for (const f of p.schemas.meta) {
      if ((f.group || '其他') !== g) {
        g = f.group || '其他';
        rows.push([g]);
      }
      rows.push([f.label, displayValue(f, p.meta[f.key], vmap), f.unit ?? '']);
      if (f.key === 'commonCK') rows.push(['供试品种数', p.varieties.length, '个']);
    }
    const area = harvestArea(p.meta);
    rows.push(['收获面积(计算)', nf(area), 'm²']);
    rows.push(['北向角', p.northDeg, '°（真北相对图纸上方顺时针）']);
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [36, 40, 12]), '基本情况');
  }

  /* 田间种植图（记载本样式） */
  {
    const head: Cell[] = [''];
    const sub: Cell[] = [''];
    const merges: Merge[] = [];
    const cols = reps.map((r) => plots.filter((x) => x.rep === r));
    reps.forEach((r, i) => {
      head.push(`重复${roman(r)}`, '');
      sub.push('小区号', '品种名');
      merges.push(mg(0, 1 + i * 2, 1, 2));
    });
    head.push('', '品种');
    sub.push('', '');
    const rows: Cell[][] = [head, sub];
    const n = Math.max(p.varieties.length, ...cols.map((c) => c.length), 0);
    for (let i = 0; i < n; i++) {
      const row: Cell[] = [i === Math.floor(n / 2) ? dirLabel(p.northDeg) : ''];
      for (const c of cols) row.push(c[i]?.node.data.plotNo ?? '', vName(c[i]?.node.data.variety));
      row.push('', p.varieties[i] ? `${p.varieties[i].code} ${p.varieties[i].name}` : '');
      rows.push(row);
    }
    rows.push([], [`${p.meta.year || ''}年度试点田间种植图`]);
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [6, ...reps.flatMap(() => [10, 16]), 4, 18]), '田间种植图');
  }

  /* 种植图布局（按实际网格，含合并） */
  {
    const rows: Cell[][] = [];
    const merges: Merge[] = [];
    const writeGrid = (node: PNode, depth: number, title: string) => {
      if (!node.grid) return;
      rows.push([title, `${node.grid.rows.length}行 × ${node.grid.cols.length}列`, `列宽(m): ${node.grid.cols.map((v) => fmt(v)).join(', ')}`]);
      rows.push(['', `行高(m): ${node.grid.rows.map((v) => fmt(v)).join(', ')}`]);
      const base = rows.length;
      for (let r = 0; r < node.grid.rows.length; r++) rows.push(new Array(node.grid.cols.length + 1).fill(''));
      for (const a of anchors(node.grid)) {
        const ch = node.children[keyOf(a.r, a.c)];
        if (!ch) continue;
        let text = ch.kind !== 'normal' ? ch.data.name || KIND_LABELS[ch.kind] : nodeTitle(ch, depth + 1);
        if (depth + 1 === 2 && ch.kind === 'normal') text = [ch.data.plotNo, vName(ch.data.variety)].filter(Boolean).join(' ');
        if (ch.fit) text += `（实际 ${fmt(ch.fit.w)}×${fmt(ch.fit.h)}m）`;
        rows[base + a.r][1 + a.c] = text;
        if (a.rs * a.cs > 1) merges.push(mg(base + a.r, 1 + a.c, a.rs, a.cs));
      }
      rows.push([]);
    };
    writeGrid(p.field, 0, '试验田');
    walk(p.field, (n, depth) => {
      if (depth === 1 && n.grid && n.kind === 'normal') writeGrid(n, 1, nodeTitle(n, 1));
    });
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [10, ...new Array(40).fill(14)]), '种植图布局');
  }

  /* 田间记载（按重复） */
  {
    const defs = p.schemas.plot.filter((f) => f.key !== 'plotNo' && f.key !== 'variety' && f.group !== '室内考种');
    const rows: Cell[][] = [];
    const merges: Merge[] = [];
    reps.forEach((r, i) => {
      merges.push(mg(rows.length, 0, 1, defs.length + 2));
      rows.push([`四-${i + 1}、田间记载（${roman(r)}重复）`]);
      rows.push(['区号', '品种编号', ...defs.map((f) => f.label)]);
      rows.push(['', '', ...defs.map((f) => unitOf(f))]);
      for (const x of plots.filter((y) => y.rep === r)) {
        const v = vmap.get(x.node.data.variety);
        rows.push([x.node.data.plotNo ?? '', v ? v.code || v.name : '', ...defs.map((f) => cellVal(f, x.node.data[f.key], vmap))]);
      }
      rows.push([]);
    });
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [8, 10, ...defs.map(() => 11)]), '田间记载');
  }

  /* 性状汇总表 */
  {
    const defs = p.schemas.plot.filter(
      (f) => !['plotNo', 'variety', 'plotYield', 'fieldEval'].includes(f.key) && f.group !== '室内考种' && f.type !== 'textarea',
    );
    const ts = traitSummary(p, defs);
    const rows: Cell[][] = [
      ['五、生育期、茎蘖动态、抗逆性汇总表'],
      ['品种(系)编号', '品种名称', '全生育期', '成穗率%', ...defs.map((f) => f.label)],
    ];
    for (const t of ts)
      rows.push([t.variety.code, t.variety.name, numOr(t.growthDays), numOr(t.spikeRate), ...defs.map((f) => (f.type === 'number' ? numOr(t.values[f.key]) : t.values[f.key]))]);
    X.utils.book_append_sheet(wb, sheet(X, rows, [mg(0, 0, 1, defs.length + 4)], [10, 16, ...defs.map(() => 10)]), '性状汇总表');
  }

  /* 室内考种结果 */
  {
    const defs = p.schemas.plot.filter((f) => f.group === '室内考种');
    const head1: Cell[] = ['品种(系)名称'];
    const head2: Cell[] = [''];
    const merges: Merge[] = [mg(0, 0, 1, 30), mg(1, 0, 2, 1)];
    for (const f of defs) {
      const c = head1.length;
      if (f.type === 'number') {
        head1.push(`${f.label}${f.unit ? `(${f.unit})` : ''}`, ...reps.map(() => ''));
        head2.push(...reps.map((r) => `第${roman(r)}重复`), '平均');
        merges.push(mg(1, c, 1, reps.length + 1));
      } else {
        head1.push(f.label);
        head2.push('');
        merges.push(mg(1, c, 2, 1));
      }
    }
    const rows: Cell[][] = [['六、室内考种结果'], head1, head2];
    for (const v of p.varieties) {
      const mine = plots.filter((x) => x.node.data.variety === v.id);
      const row: Cell[] = [v.name];
      for (const f of defs) {
        if (f.type === 'number') {
          const per = reps.map((r) => summarizeField(f, mine.filter((x) => x.rep === r).map((x) => x.node.data[f.key] ?? '')));
          row.push(...per.map(numOr), numOr(summarizeField(f, mine.map((x) => x.node.data[f.key] ?? ''))));
        } else row.push(cellVal(f, summarizeField(f, mine.map((x) => x.node.data[f.key] ?? '')), vmap));
      }
      rows.push(row);
    }
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [16, ...new Array(40).fill(9)]), '考种结果');
  }

  /* 产量表 */
  {
    const yt = yieldTable(p, 'mean');
    const n = yt.reps.length;
    const rows: Cell[][] = [
      ['九、产量表'],
      ['', '小区产量（kg）', ...new Array(n).fill(''), '折亩产（kg）', ...new Array(n).fill(''), '', '位次'],
      ['品种代号', ...yt.reps.map((r) => roman(r)), '平均', ...yt.reps.map((r) => roman(r)), '平均', '±%', '', '品种名称'],
    ];
    for (const r of yt.rows)
      rows.push([
        r.variety.code,
        ...r.plot.map((v) => nf(v)),
        nf(r.plotMean),
        ...r.mu.map((v) => nf(v, 1)),
        nf(r.muMean, 1),
        nf(r.diff),
        r.rank ?? '',
        r.variety.name + (r.variety.role === 'ck' ? '(CK)' : ''),
      ]);
    rows.push([], ['注：产量表中的增产率是以试验平均产量为基础的计算结果'], ['收获面积(m²)', nf(yt.area)]);
    rows.push(['试验单位', p.meta.testUnit ?? ''], ['负责人', p.meta.contact ?? ''], ['联系电话', p.meta.phone ?? '']);
    const merges = [mg(0, 0, 1, 2 * n + 6), mg(1, 1, 1, n + 1), mg(1, n + 2, 1, n + 1)];
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [10, ...new Array(2 * n + 2).fill(9), 8, 6, 18]), '产量表');
  }

  /* 气象资料 */
  {
    const rows: Cell[][] = [
      [`三、${p.meta.year || ''}年度小麦生育期间气象资料表`],
      ['', '', ...WEATHER_MONTHS.flatMap((m) => [`${m}月`, ''])],
      ['', '', ...WEATHER_MONTHS.flatMap(() => ['当年', '常年'])],
    ];
    const merges = [mg(0, 0, 1, 22), ...WEATHER_MONTHS.map((_, i) => mg(1, 2 + i * 2, 1, 2))];
    for (const r of WEATHER_ROWS)
      rows.push([r.group, r.label, ...WEATHER_MONTHS.flatMap((m) => [p.weather[`${r.key}|${m}|cur`] ?? '', p.weather[`${r.key}|${m}|norm`] ?? ''])]);
    X.utils.book_append_sheet(wb, sheet(X, rows, merges, [14, 16, ...new Array(20).fill(7)]), '气象资料');
  }

  /* 品种与评价 */
  {
    const rows: Cell[][] = [['代号', '品种名称', '类别', '选育/供种单位', '备注', '品种评价']];
    for (const v of p.varieties)
      rows.push([v.code, v.name, v.role === 'ck' ? '共同对照' : v.role === 'ck2' ? '辅助对照' : '参试', v.source ?? '', v.note ?? '', v.evaluation ?? '']);
    rows.push([], ['气象分析', p.weatherAnalysis]);
    X.utils.book_append_sheet(wb, sheet(X, rows, [], [8, 16, 10, 18, 16, 60]), '品种评价');
  }

  X.writeFile(wb, `${safeName(p.name || '记载本')}.xlsx`);
}

const nf = (v: number | null | undefined, d = 2): Cell => (v == null || !isFinite(v) ? '' : Math.round(v * 10 ** d) / 10 ** d);
const numOr = (s: string | undefined): Cell => (s != null && s !== '' && isFinite(Number(s)) ? Number(s) : s ?? '');

function unitOf(f: FieldDef) {
  if (f.type === 'date') return 'YY-MM-DD';
  return f.unit ? `(${f.unit})` : '';
}

function cellVal(f: FieldDef, v: string | undefined, vmap: Map<string, Variety>): Cell {
  if (v == null || v === '') return '';
  if (f.type === 'number') {
    const n = Number(v);
    return isFinite(n) ? n : v;
  }
  if (f.type === 'select') return v;
  return displayValue(f, v, vmap);
}

function dirLabel(northDeg: number) {
  const d = ((northDeg % 360) + 360) % 360;
  if (d === 270) return '北';
  return `北向${d}°`;
}

const safeName = (s: string) => s.replace(/[\\/:*?"<>|]/g, '_');

/* ---------------- 导入记载本模板 ---------------- */

const norm = (s: unknown) =>
  String(s ?? '')
    .replace(/\s+/g, '')
    .replace(/[：:]/g, '')
    .replace(/^\d+[、.]/, '')
    .replace(/[（(].*$/, '');

const UNITS = new Set(['个', '米', '行', '厘米', '亩', '万苗/亩', 'm', 'cm']);

export interface ImportResult {
  project: Project;
  notes: string[];
}

export async function importTemplate(file: File, base: Project): Promise<ImportResult> {
  const X = await loadXLSX();
  const wb = X.read(await file.arrayBuffer(), { type: 'array' });
  const notes: string[] = [];
  const p: Project = JSON.parse(JSON.stringify(base));
  const aoa = (name: string): string[][] => {
    const ws = wb.Sheets[name];
    if (!ws) return [];
    return (X.utils.sheet_to_json(ws, { header: 1, defval: '', raw: false }) as unknown[][]).map((r) => r.map((c) => String(c ?? '').trim()));
  };

  /* 田间种植图 */
  const rows = aoa('田间种植图');
  const hr = rows.findIndex((r) => r.some((c) => /^重复/.test(c)));
  if (hr >= 0) {
    const repCols: { col: number; rep: number }[] = [];
    rows[hr].forEach((c, i) => {
      const m = /^重复\s*(.+)$/.exec(c);
      if (m) repCols.push({ col: i, rep: romanToNum(m[1]) || repCols.length + 1 });
    });
    const lastRepCol = repCols[repCols.length - 1]?.col ?? 0;
    const varietyCol = (rows[hr + 1] ?? []).findIndex((c, i) => c === '品种' && i > lastRepCol + 1);
    const vcol = varietyCol >= 0 ? varietyCol : (rows[hr] ?? []).findIndex((c, i) => c === '品种' && i > lastRepCol + 1);
    let end = rows.length;
    for (let r = hr + 2; r < rows.length; r++) if (rows[r].some((c) => /种植图|说明/.test(c))) {
      end = r;
      break;
    }
    const lists = repCols.map(({ col }) => {
      const out: { no: string; name: string }[] = [];
      for (let r = hr + 2; r < end; r++) {
        const no = rows[r][col] ?? '';
        const name = rows[r][col + 1] ?? '';
        if (no || name) out.push({ no, name });
      }
      return out;
    });
    // 品种表：导入了种植图时以文件中的品种替换示例品种
    const names: string[] = [];
    const addName = (n: string) => n && n !== '品种' && n !== '保护区' && !names.includes(n) && names.push(n);
    if (vcol >= 0) for (let r = hr + 1; r < end; r++) addName(rows[r][vcol]);
    for (const l of lists) for (const it of l) addName(it.name);
    const hasPlots = lists.some((l) => l.length);
    if (hasPlots) p.varieties = names.map((name, i) => ({ id: uid('v'), code: String(i + 1), name, role: 'test' as const }));
    else for (const name of names) if (!p.varieties.some((v) => v.name === name)) p.varieties.push({ id: uid('v'), code: String(p.varieties.length + 1), name, role: 'test' });
    // 北（仅在导入了种植图时应用）
    const maxN0 = Math.max(0, ...lists.map((l) => l.length));
    for (let r = 0; maxN0 > 0 && r < end; r++) {
      const c = rows[r].findIndex((x) => x === '北');
      if (c < 0) continue;
      const first = repCols[0]?.col ?? 1;
      const last = (repCols[repCols.length - 1]?.col ?? 1) + 1;
      p.northDeg = c < first ? 270 : c > last ? 90 : r < hr ? 0 : 180;
      notes.push(`指北：${{ 0: '北在上', 90: '北在右', 180: '北在下', 270: '北在左' }[p.northDeg]}`);
      break;
    }
    const maxN = Math.max(0, ...lists.map((l) => l.length));
    if (repCols.length && maxN) {
      const L = Number(p.meta.plotLength) || 6.67;
      const W = Number(p.meta.plotWidth) || 2;
      const field = generateField(
        { reps: repCols.length, repRows: maxN, repCols: 1, plotW: L, plotL: W, protect: 2, alley: 1, direction: 'h', numberBase: 100, assign: 'none' },
        [],
      );
      let bi = 0;
      walk(field, (n, depth) => {
        if (depth !== 1 || n.kind !== 'normal' || !n.grid) return;
        const list = lists[bi];
        n.data.rep = String(repCols[bi].rep);
        n.data.name = `重复${roman(repCols[bi].rep)}`;
        bi++;
        for (let r = 0; r < n.grid.rows.length; r++) {
          const ch = n.children[keyOf(r, 0)];
          const it = list[r];
          if (!it) {
            ch.kind = 'blank';
            ch.data = {};
            continue;
          }
          ch.data = { plotNo: it.no, variety: p.varieties.find((v) => v.name === it.name)?.id ?? '' };
        }
      });
      p.field = field;
      p.meta.reps = String(repCols.length);
      notes.push(`田间种植图：${repCols.length} 个重复，${lists.reduce((s, l) => s + l.length, 0)} 个小区`);
    } else notes.push('田间种植图为空，保留当前布局');
  }
  /* 基本情况 + 封面 */
  const year = aoa('封面')
    .flat()
    .find((c) => /^\d{4}-\d{4}$/.test(c));
  if (year) p.meta.year = year;
  const labelMap = new Map(p.schemas.meta.map((f) => [norm(f.label), f]));
  let metaCount = 0;
  for (const name of ['封面', '基本情况']) {
    const rows = aoa(name);
    rows.forEach((row) => {
      row.forEach((cell, c) => {
        const f = labelMap.get(norm(cell));
        if (!f) return;
        for (let j = c + 1; j < row.length; j++) {
          const v = row[j];
          if (!v) continue;
          if (UNITS.has(v) || labelMap.has(norm(v))) break;
          if (f.type === 'variety') {
            let vv = p.varieties.find((x) => x.name === v);
            if (!vv) {
              vv = { id: uid('v'), code: String(p.varieties.length + 1), name: v, role: 'ck' };
              p.varieties.push(vv);
            }
            if (f.key === 'commonCK') vv.role = 'ck';
            p.meta[f.key] = vv.id;
          } else p.meta[f.key] = f.type === 'date' ? toISODate(v, p.meta.year) : v;
          metaCount++;
          break;
        }
      });
    });
  }
  if (metaCount) notes.push(`基本情况：读取 ${metaCount} 项`);

  if (p.varieties.length) notes.push(`品种：共 ${p.varieties.length} 个`);
  p.updatedAt = new Date().toISOString();
  return { project: p, notes };
}

/** 各种日期写法 → YYYY-MM-DD；只有月日时按年度推断年份（8月及以后为前一年） */
export function toISODate(v: string, yearRange = ''): string {
  const t = v.trim();
  let m = /^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$/.exec(t);
  if (m) return `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`;
  m = /^(\d{1,2})[-/.月](\d{1,2})日?$/.exec(t);
  if (m) {
    const ys = /^(\d{4})-(\d{4})$/.exec(yearRange);
    const mon = Number(m[1]);
    const y = ys ? (mon >= 8 ? ys[1] : ys[2]) : String(new Date().getFullYear());
    return `${y}-${m[1].padStart(2, '0')}-${m[2].padStart(2, '0')}`;
  }
  m = /^(\d{1,2})\/(\d{1,2})\/(\d{2})$/.exec(t);
  if (m) return `20${m[3]}-${m[1].padStart(2, '0')}-${m[2].padStart(2, '0')}`;
  return t;
}

function romanToNum(s: string): number {
  const ROM = ['Ⅰ', 'Ⅱ', 'Ⅲ', 'Ⅳ', 'Ⅴ', 'Ⅵ', 'Ⅶ', 'Ⅷ', 'Ⅸ', 'Ⅹ'];
  const i = ROM.indexOf(s.trim());
  if (i >= 0) return i + 1;
  const map: Record<string, number> = { I: 1, II: 2, III: 3, IV: 4, V: 5, VI: 6 };
  return map[s.trim().toUpperCase()] ?? (Number(s) || 0);
}

/* ---------------- JSON ---------------- */

export function exportJSON(p: Project) {
  const blob = new Blob([JSON.stringify(p, null, 1)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `${safeName(p.name || '记载本')}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

export async function readJSON(file: File): Promise<Project> {
  const p = JSON.parse(await file.text());
  if (!p || !p.field || !p.field.id) throw new Error('不是有效的项目文件');
  return p as Project;
}
