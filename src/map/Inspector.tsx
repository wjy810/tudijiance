import { useMemo, useState } from 'react';
import { FieldForm, FieldInput, NumInput, Section } from '../components/inputs';
import { Icon } from '../components/Icon';
import { assignVarieties, numberPlots, type NumberOrder } from '../model/defaults';
import { KIND_LABELS, nodeTitle } from '../model/display';
import { depthName, levelOfDepth } from '../model/fields';
import {
  clearSubdivision,
  deleteTrack,
  equalizeTracks,
  fillSlot,
  insertTrack,
  locate,
  mergeRange,
  moveNode,
  parseKey,
  rangeOfKeys,
  resizeNode,
  setAllTracks,
  setTrackCount,
  setTrackSize,
  spanOf,
  subdivide,
  sum,
  unmerge,
  walk,
  type Axis,
} from '../model/grid';
import { computeLayout, type LayoutItem } from '../model/layout';
import type { CellKind, PNode } from '../model/types';
import { projectActions, uiStore, useProject, useStore, viewStore } from '../store/store';
import { GenDialog } from './GenDialog';

const upd = projectActions.update;

export function Inspector() {
  const project = useProject();
  const ui = useStore(uiStore);
  const layout = useMemo(() => computeLayout(project.field), [project.field]);
  const active = layout.byId.get(ui.activeId) ?? layout.items[0];
  const selItems = ui.selection
    .map((k) => active.node.children[k])
    .filter(Boolean)
    .map((n) => layout.byId.get(n.id)!)
    .filter(Boolean);
  const childDepth = active.depth + 1;
  const [showGen, setShowGen] = useState(false);

  return (
    <aside className="inspector">
      {selItems.length > 0 ? (
        <SelectionPanel items={selItems} container={active} depth={childDepth} />
      ) : (
        <div className="empty-sel">
          在图上单击选择{depthName(childDepth)}；Shift 单击选择矩形范围，Ctrl 单击多选；点击上方/左侧标尺选择整列/整行。
        </div>
      )}
      {active.node.grid && <GridPanel item={active} />}
      {active.depth === 0 && <FieldPanel onGenerate={() => setShowGen(true)} />}
      {showGen && <GenDialog onClose={() => setShowGen(false)} />}
    </aside>
  );
}

/* ---------------- 选中对象 ---------------- */

function SelectionPanel({ items, container, depth }: { items: LayoutItem[]; container: LayoutItem; depth: number }) {
  const project = useProject();
  const single = items.length === 1 ? items[0] : null;
  const g = container.node.grid!;
  const keys = items.map((i) => i.key!);
  const level = levelOfDepth(depth);
  const defs = project.schemas[level];
  const [batchKey, setBatchKey] = useState('');
  const [batchVal, setBatchVal] = useState('');
  const [sub, setSub] = useState({ r: 1, c: 6 });

  const span = single ? spanOf(g, single.key!) : null;
  const isMerged = !!span && span.rs * span.cs > 1;
  const kinds = new Set(items.map((i) => i.node.kind));

  const doMerge = () => {
    const rg = rangeOfKeys(g, keys)!;
    let key: string | null = null;
    upd((d) => {
      key = mergeRange(d.field, container.id, rg);
    });
    if (key) uiStore.set((s) => ({ ...s, selection: [key!], anchorKey: key }));
  };

  const setKind = (k: CellKind) =>
    upd((d) => {
      for (const it of items) {
        const loc = locate(d.field, it.id);
        if (loc) loc.node.kind = k;
      }
    });

  const enter = (n: PNode) => uiStore.set((s) => ({ ...s, activeId: n.id, selection: [], anchorKey: null }));

  return (
    <Section
      title={
        single ? (
          <>
            {nodeTitle(single.node, depth)} <small className="muted">{depthName(depth)}</small>
          </>
        ) : (
          `已选 ${items.length} 个${depthName(depth)}`
        )
      }
    >
      <div className="btn-row">
        {items.length > 1 && (
          <button className="primary" onClick={doMerge} title="合并为一个单元（保留左上角数据）">
            <Icon name="merge" /> 合并
          </button>
        )}
        {isMerged && (
          <button onClick={() => upd((d) => unmerge(d.field, container.id, single!.key!))}>
            <Icon name="split" /> 拆分
          </button>
        )}
        {single?.node.grid && (
          <button onClick={() => enter(single.node)}>
            <Icon name="enter" /> 进入内部
          </button>
        )}
      </div>

      <div className="row-field">
        <span>类型</span>
        <select value={kinds.size === 1 ? [...kinds][0] : ''} onChange={(e) => setKind(e.target.value as CellKind)}>
          {kinds.size > 1 && <option value="">（多种）</option>}
          {(Object.keys(KIND_LABELS) as CellKind[]).map((k) => (
            <option key={k} value={k}>
              {KIND_LABELS[k]}
            </option>
          ))}
        </select>
      </div>

      {single && <SizeEditor item={single} />}
      {!single && <BatchSize items={items} />}

      {single && (
        <div className="subgrid">
          <h4>内部划分（{depth === 1 ? '小区' : '行/列'}）</h4>
          {single.node.grid ? (
            <div className="btn-row">
              <span className="muted">
                {single.node.grid.rows.length} 行 × {single.node.grid.cols.length} 列
              </span>
              <button onClick={() => enter(single.node)}>编辑网格</button>
              <button
                className="danger"
                onClick={() => {
                  if (confirm('取消细分将删除内部所有单元及其数据，确定？')) upd((d) => clearSubdivision(d.field, single.id));
                }}
              >
                取消细分
              </button>
            </div>
          ) : (
            <div className="btn-row">
              <label className="inline">
                行
                <input type="number" min={1} value={sub.r} onChange={(e) => setSub({ ...sub, r: Number(e.target.value) })} />
              </label>
              <label className="inline">
                列
                <input type="number" min={1} value={sub.c} onChange={(e) => setSub({ ...sub, c: Number(e.target.value) })} />
              </label>
              <button
                onClick={() => {
                  upd((d) => subdivide(d.field, single.id, sub.r, sub.c));
                }}
              >
                <Icon name="grid" /> 细分
              </button>
            </div>
          )}
        </div>
      )}

      {single && single.node.kind === 'normal' && (
        <>
          <h4>字段</h4>
          <FieldForm
            defs={defs}
            data={single.node.data}
            varieties={project.varieties}
            onChange={(key, v) =>
              upd(
                (d) => {
                  const loc = locate(d.field, single.id);
                  if (loc) loc.node.data[key] = v;
                },
                { coalesce: `f:${single.id}:${key}` },
              )
            }
          />
        </>
      )}
      {single && single.node.kind !== 'normal' && (
        <label className="row-field">
          <span>名称</span>
          <input
            value={single.node.data.name ?? ''}
            onChange={(e) =>
              upd(
                (d) => {
                  const loc = locate(d.field, single.id);
                  if (loc) loc.node.data.name = e.target.value;
                },
                { coalesce: `n:${single.id}` },
              )
            }
          />
        </label>
      )}

      {!single && (
        <div className="batch">
          <h4>批量填写</h4>
          <div className="row-field">
            <span>字段</span>
            <select value={batchKey} onChange={(e) => setBatchKey(e.target.value)}>
              <option value="">选择字段…</option>
              {defs.map((d) => (
                <option key={d.key} value={d.key}>
                  {d.label}
                </option>
              ))}
            </select>
          </div>
          {batchKey && (
            <div className="row-field">
              <span>值</span>
              <FieldInput def={defs.find((d) => d.key === batchKey)!} value={batchVal} onChange={setBatchVal} varieties={project.varieties} />
            </div>
          )}
          <button
            disabled={!batchKey}
            onClick={() =>
              upd((d) => {
                for (const it of items) {
                  const loc = locate(d.field, it.id);
                  if (loc) loc.node.data[batchKey] = batchVal;
                }
              })
            }
          >
            应用到 {items.length} 个
          </button>
        </div>
      )}
    </Section>
  );
}

function SizeEditor({ item }: { item: LayoutItem }) {
  const fit = item.node.fit;
  const slot = item.slot;
  const w = fit?.w ?? slot.w;
  const h = fit?.h ?? slot.h;
  const x = fit?.x ?? 0;
  const y = fit?.y ?? 0;
  const id = item.id;
  const align = (ax: 'x' | 'y', where: 0 | 0.5 | 1) =>
    upd((d) => {
      const nx = ax === 'x' ? (slot.w - w) * where : x;
      const ny = ax === 'y' ? (slot.h - h) * where : y;
      moveNode(d.field, id, nx, ny);
    });
  return (
    <div className="size-editor">
      <h4>
        尺寸 <small className="muted">原槽位 {fmt(slot.w)} × {fmt(slot.h)} m</small>
      </h4>
      <div className="grid2">
        <label>
          <span>实际宽</span>
          <NumInput value={w} min={0.05} suffix="m" onCommit={(v) => upd((d) => resizeNode(d.field, id, v, h))} />
        </label>
        <label>
          <span>实际高</span>
          <NumInput value={h} min={0.05} suffix="m" onCommit={(v) => upd((d) => resizeNode(d.field, id, w, v))} />
        </label>
        <label>
          <span>左侧路面</span>
          <NumInput value={x} min={0} suffix="m" disabled={!fit || near0(slot.w - w)} onCommit={(v) => upd((d) => moveNode(d.field, id, v, y))} />
        </label>
        <label>
          <span>上侧路面</span>
          <NumInput value={y} min={0} suffix="m" disabled={!fit || near0(slot.h - h)} onCommit={(v) => upd((d) => moveNode(d.field, id, x, v))} />
        </label>
      </div>
      {fit && (
        <>
          <div className="road-info muted">
            路面：左 {fmt(x)} · 右 {fmt(slot.w - w - x)} · 上 {fmt(y)} · 下 {fmt(slot.h - h - y)} m
          </div>
          <div className="btn-row tight">
            <button onClick={() => align('x', 0)} disabled={near0(slot.w - w)} title="靠左">
              ⇤
            </button>
            <button onClick={() => align('x', 0.5)} disabled={near0(slot.w - w)} title="水平居中">
              ↔
            </button>
            <button onClick={() => align('x', 1)} disabled={near0(slot.w - w)} title="靠右">
              ⇥
            </button>
            <button onClick={() => align('y', 0)} disabled={near0(slot.h - h)} title="靠上">
              ⤒
            </button>
            <button onClick={() => align('y', 0.5)} disabled={near0(slot.h - h)} title="垂直居中">
              ↕
            </button>
            <button onClick={() => align('y', 1)} disabled={near0(slot.h - h)} title="靠下">
              ⤓
            </button>
            <button onClick={() => upd((d) => fillSlot(d.field, id))}>恢复填满</button>
          </div>
        </>
      )}
      <p className="tip">缩小后两侧默认为路面；可在图上拖动该块（范围为原槽位），或拖动把手调整大小。</p>
    </div>
  );
}

function BatchSize({ items }: { items: LayoutItem[] }) {
  const [w, setW] = useState<number | ''>('');
  const [h, setH] = useState<number | ''>('');
  return (
    <div className="size-editor">
      <h4>统一尺寸</h4>
      <div className="grid2">
        <label>
          <span>宽</span>
          <span className="num-input">
            <input type="number" step="0.01" value={w} placeholder="不变" onChange={(e) => setW(e.target.value === '' ? '' : Number(e.target.value))} />
            <em>m</em>
          </span>
        </label>
        <label>
          <span>高</span>
          <span className="num-input">
            <input type="number" step="0.01" value={h} placeholder="不变" onChange={(e) => setH(e.target.value === '' ? '' : Number(e.target.value))} />
            <em>m</em>
          </span>
        </label>
      </div>
      <div className="btn-row">
        <button
          disabled={w === '' && h === ''}
          onClick={() =>
            upd((d) => {
              for (const it of items) {
                const cur = it.node.fit ?? it.slot;
                resizeNode(d.field, it.id, w === '' ? cur.w : Math.min(w, it.slot.w), h === '' ? cur.h : Math.min(h, it.slot.h));
              }
            })
          }
        >
          应用（居中，两侧为路面）
        </button>
        <button
          onClick={() =>
            upd((d) => {
              for (const it of items) fillSlot(d.field, it.id);
            })
          }
        >
          恢复填满
        </button>
      </div>
    </div>
  );
}

/* ---------------- 网格设置 ---------------- */

function GridPanel({ item }: { item: LayoutItem }) {
  const project = useProject();
  const ui = useStore(uiStore);
  const g = item.node.grid!;
  const id = item.id;
  const childName = depthName(item.depth + 1);
  const [allCol, setAllCol] = useState<number | ''>('');
  const [allRow, setAllRow] = useState<number | ''>('');
  const [numStart, setNumStart] = useState(() => (Number(item.node.data.rep) || 1) * 100 + 1);
  const [order, setOrder] = useState<NumberOrder>('row');
  const selRange = ui.activeId === id ? rangeOfKeys(g, ui.selection.filter((k) => item.node.children[k])) : null;

  const tracksEditor = (axis: Axis) => {
    const arr = axis === 'col' ? g.cols : g.rows;
    return (
      <div className="tracks">
        {arr.map((v, i) => (
          <label key={i} className={selRange && i >= (axis === 'col' ? selRange.c0 : selRange.r0) && i <= (axis === 'col' ? selRange.c1 : selRange.r1) ? 'on' : ''}>
            <span>{i + 1}</span>
            <NumInput value={v} min={0.05} ariaLabel={`${axis === 'col' ? '列' : '行'}${i + 1}`} onCommit={(n) => upd((d) => setTrackSize(d.field, id, axis, i, n))} />
          </label>
        ))}
      </div>
    );
  };

  const mergeLine = (axis: Axis) => {
    if (!selRange) return;
    upd((d) => {
      const loc = locate(d.field, id)!;
      const gg = loc.node.grid!;
      if (axis === 'row') for (let r = selRange.r0; r <= selRange.r1; r++) mergeRange(d.field, id, { r0: r, r1: r, c0: 0, c1: gg.cols.length - 1 });
      else for (let c = selRange.c0; c <= selRange.c1; c++) mergeRange(d.field, id, { r0: 0, r1: gg.rows.length - 1, c0: c, c1: c });
    });
    uiStore.set((s) => ({ ...s, selection: [] }));
  };

  const unmergeAll = () =>
    upd((d) => {
      const loc = locate(d.field, id)!;
      for (const k of ui.selection) {
        const [r, c] = parseKey(k);
        if (loc.node.grid!.merges.some((m) => m.r === r && m.c === c)) unmerge(d.field, id, k);
      }
    });

  return (
    <Section title={`${nodeTitle(item.node, item.depth)} · 网格（${childName}）`}>
      <div className="grid2">
        <label>
          <span>行数</span>
          <NumInput value={g.rows.length} digits={0} step={1} min={1} onCommit={(n) => upd((d) => setTrackCount(d.field, id, 'row', n))} />
        </label>
        <label>
          <span>列数</span>
          <NumInput value={g.cols.length} digits={0} step={1} min={1} onCommit={(n) => upd((d) => setTrackCount(d.field, id, 'col', n))} />
        </label>
      </div>
      <div className="muted small">
        总尺寸 {fmt(sum(g.cols))} × {fmt(sum(g.rows))} m · 修改行列数时保持总尺寸并均分；修改单行/列尺寸会改变总尺寸（超出原范围时自动加宽上级）。
      </div>

      <h4>
        每列宽度 (m)
        <span className="h-actions">
          <button className="mini" onClick={() => upd((d) => equalizeTracks(d.field, id, 'col'))}>
            均分
          </button>
          <span className="num-input mini">
            <input type="number" step="0.01" placeholder="统一" value={allCol} onChange={(e) => setAllCol(e.target.value === '' ? '' : Number(e.target.value))} />
          </span>
          <button className="mini" disabled={allCol === ''} onClick={() => allCol !== '' && upd((d) => setAllTracks(d.field, id, 'col', allCol))}>
            设
          </button>
        </span>
      </h4>
      {tracksEditor('col')}
      <h4>
        每行高度 (m)
        <span className="h-actions">
          <button className="mini" onClick={() => upd((d) => equalizeTracks(d.field, id, 'row'))}>
            均分
          </button>
          <span className="num-input mini">
            <input type="number" step="0.01" placeholder="统一" value={allRow} onChange={(e) => setAllRow(e.target.value === '' ? '' : Number(e.target.value))} />
          </span>
          <button className="mini" disabled={allRow === ''} onClick={() => allRow !== '' && upd((d) => setAllTracks(d.field, id, 'row', allRow))}>
            设
          </button>
        </span>
      </h4>
      {tracksEditor('row')}

      <h4>行/列操作 {selRange ? <small className="muted">（按当前选区）</small> : <small className="muted">（先选择单元）</small>}</h4>
      <div className="btn-row wrap">
        <button disabled={!selRange} onClick={() => mergeLine('row')} title="把选区所在的每一行各自合并">
          合并整行
        </button>
        <button disabled={!selRange} onClick={() => mergeLine('col')} title="把选区所在的每一列各自合并">
          合并整列
        </button>
        <button disabled={!ui.selection.length} onClick={unmergeAll}>
          拆分所选
        </button>
      </div>
      <div className="btn-row wrap">
        <button disabled={!selRange} onClick={() => selRange && upd((d) => insertTrack(d.field, id, 'row', selRange.r0))}>
          上方插入行
        </button>
        <button disabled={!selRange} onClick={() => selRange && upd((d) => insertTrack(d.field, id, 'row', selRange.r1 + 1))}>
          下方插入行
        </button>
        <button disabled={!selRange} onClick={() => selRange && upd((d) => insertTrack(d.field, id, 'col', selRange.c0))}>
          左侧插入列
        </button>
        <button disabled={!selRange} onClick={() => selRange && upd((d) => insertTrack(d.field, id, 'col', selRange.c1 + 1))}>
          右侧插入列
        </button>
      </div>
      <div className="btn-row wrap">
        <button
          className="danger"
          disabled={!selRange || g.rows.length <= 1}
          onClick={() => {
            if (!selRange) return;
            upd((d) => {
              for (let r = selRange.r1; r >= selRange.r0; r--) deleteTrack(d.field, id, 'row', r);
            });
            uiStore.set((s) => ({ ...s, selection: [] }));
          }}
        >
          删除所在行
        </button>
        <button
          className="danger"
          disabled={!selRange || g.cols.length <= 1}
          onClick={() => {
            if (!selRange) return;
            upd((d) => {
              for (let c = selRange.c1; c >= selRange.c0; c--) deleteTrack(d.field, id, 'col', c);
            });
            uiStore.set((s) => ({ ...s, selection: [] }));
          }}
        >
          删除所在列
        </button>
      </div>

      {item.depth === 1 && (
        <>
          <h4>小区编号与品种</h4>
          <div className="grid2">
            <label>
              <span>起始编号</span>
              <input type="number" value={numStart} onChange={(e) => setNumStart(Number(e.target.value))} />
            </label>
            <label>
              <span>顺序</span>
              <select value={order} onChange={(e) => setOrder(e.target.value as NumberOrder)}>
                <option value="row">逐行</option>
                <option value="col">逐列</option>
                <option value="snake">蛇形</option>
              </select>
            </label>
          </div>
          <div className="btn-row wrap">
            <button
              onClick={() =>
                upd((d) => {
                  const loc = locate(d.field, id);
                  if (loc) numberPlots(loc.node, numStart, order);
                })
              }
            >
              自动编号
            </button>
            <button
              disabled={!project.varieties.length}
              onClick={() =>
                upd((d) => {
                  const loc = locate(d.field, id);
                  if (loc) assignVarieties(loc.node, d.varieties, 'seq', order);
                })
              }
            >
              顺序分配品种
            </button>
            <button
              disabled={!project.varieties.length}
              onClick={() =>
                upd((d) => {
                  const loc = locate(d.field, id);
                  if (loc) assignVarieties(loc.node, d.varieties, 'random', order);
                })
              }
            >
              随机分配品种
            </button>
          </div>
        </>
      )}
    </Section>
  );
}

/* ---------------- 试验田 ---------------- */

function FieldPanel({ onGenerate }: { onGenerate: () => void }) {
  const project = useProject();
  const stats = useMemo(() => {
    let blocks = 0;
    let plots = 0;
    let road = 0;
    walk(project.field, (n, depth) => {
      if (depth === 1 && n.kind === 'normal') blocks++;
      if (depth === 2 && n.kind === 'normal') plots++;
      if (n.kind === 'road') road++;
    });
    return { blocks, plots, road };
  }, [project.field]);
  const setNorth = (deg: number) => upd((d) => void (d.northDeg = ((Math.round(deg) % 360) + 360) % 360));
  const rot = viewStore.get().rot;
  return (
    <Section title="试验田与方位">
      <div className="muted small">
        区块 {stats.blocks} 个 · 小区 {stats.plots} 个 · 尺寸 {fmt(sum(project.field.grid?.cols ?? []))} ×{' '}
        {fmt(sum(project.field.grid?.rows ?? []))} m
      </div>
      <h4>指北方向</h4>
      <div className="row-field">
        <span>北向角</span>
        <NumInput value={project.northDeg} digits={0} step={1} suffix="°" onCommit={setNorth} />
      </div>
      <div className="btn-row tight">
        <button onClick={() => setNorth(0)}>北在上</button>
        <button onClick={() => setNorth(90)}>北在右</button>
        <button onClick={() => setNorth(180)}>北在下</button>
        <button onClick={() => setNorth(270)}>北在左</button>
      </div>
      <div className="btn-row">
        <button onClick={() => setNorth(-viewStore.get().rot)} title="先把视角旋转到与实地一致，再点此按钮">
          以当前屏幕上方为北
        </button>
      </div>
      <p className="tip">北向角：真北相对图纸上方的顺时针角度。当前视角 {Math.round(rot)}°。</p>
      <h4>快速生成</h4>
      <button className="primary" onClick={onGenerate}>
        <Icon name="wand" /> 按重复/品种生成种植图…
      </button>
    </Section>
  );
}

const fmt = (v: number) => String(Math.round(v * 100) / 100);
const near0 = (v: number) => Math.abs(v) < 1e-4;
