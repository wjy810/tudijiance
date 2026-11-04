import { useMemo, useState } from 'react';
import { FieldInput } from '../components/inputs';
import { collectPlots, repList } from '../model/calc';
import { roman } from '../model/defaults';
import { locate } from '../model/grid';
import { projectActions, uiStore, useProject } from '../store/store';

/** 四、田间记载（及室内考种）——按重复分表、按字段分组显示 */
export function RecordsView() {
  const project = useProject();
  const plots = useMemo(() => collectPlots(project), [project]);
  const reps = repList(project, plots);
  const defs = project.schemas.plot.filter((f) => f.key !== 'plotNo' && f.key !== 'variety');
  const groups = [...new Set(defs.map((f) => f.group || '其他'))];
  const [rep, setRep] = useState<number | 0>(0);
  const [group, setGroup] = useState<string>(groups[0] ?? '');
  const cols = group === '*' ? defs : defs.filter((f) => (f.group || '其他') === group);
  const rows = plots.filter((p) => !rep || p.rep === rep);
  const plotNoDef = project.schemas.plot.find((f) => f.key === 'plotNo')!;
  const varietyDef = project.schemas.plot.find((f) => f.key === 'variety')!;

  const set = (id: string, key: string, v: string) =>
    projectActions.update(
      (d) => {
        const loc = locate(d.field, id);
        if (loc) loc.node.data[key] = v;
      },
      { coalesce: `f:${id}:${key}` },
    );

  const filled = (key: string) => rows.filter((r) => r.node.data[key]).length;

  return (
    <div className="page wide">
      <div className="page-head">
        <h1>田间记载</h1>
        <p className="muted">每个小区一行；数据与种植图同步。日期格式 年-月-日。</p>
      </div>
      <div className="filters">
        <div className="seg-group" role="tablist" aria-label="重复">
          <button className={rep === 0 ? 'on' : ''} onClick={() => setRep(0)}>
            全部
          </button>
          {reps.map((r) => (
            <button key={r} className={rep === r ? 'on' : ''} onClick={() => setRep(r)}>
              重复{roman(r)}
            </button>
          ))}
        </div>
        <div className="seg-group" role="tablist" aria-label="字段分组">
          {groups.map((g) => (
            <button key={g} className={group === g ? 'on' : ''} onClick={() => setGroup(g)}>
              {g}
            </button>
          ))}
          <button className={group === '*' ? 'on' : ''} onClick={() => setGroup('*')}>
            全部字段
          </button>
        </div>
      </div>
      {!rows.length ? (
        <div className="card muted">暂无小区。请先在“种植图”中划分区块与小区。</div>
      ) : (
        <div className="table-scroll">
          <table className="tbl records">
            <thead>
              <tr>
                <th className="sticky c0">重复</th>
                <th className="sticky c1">小区号</th>
                <th className="sticky c2">品种</th>
                {cols.map((f) => (
                  <th key={f.key} title={f.hint}>
                    {f.label}
                    {f.unit && <em>{f.unit}</em>}
                    <small className="fill">
                      {filled(f.key)}/{rows.length}
                    </small>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.node.id}>
                  <td className="sticky c0">
                    <button
                      className="link"
                      title="在种植图中定位"
                      onClick={() => {
                        const loc = locate(project.field, p.node.id);
                        if (!loc?.parent || !loc.key) return;
                        uiStore.set((s) => ({ ...s, tab: 'map', activeId: loc.parent!.id, selection: [loc.key!], anchorKey: loc.key }));
                      }}
                    >
                      {roman(p.rep)}
                    </button>
                  </td>
                  <td className="sticky c1">
                    <FieldInput compact def={plotNoDef} value={p.node.data.plotNo} onChange={(v) => set(p.node.id, 'plotNo', v)} varieties={project.varieties} />
                  </td>
                  <td className="sticky c2">
                    <FieldInput compact def={varietyDef} value={p.node.data.variety} onChange={(v) => set(p.node.id, 'variety', v)} varieties={project.varieties} />
                  </td>
                  {cols.map((f) => (
                    <td key={f.key} className={`t-${f.type}`}>
                      <FieldInput compact def={f} value={p.node.data[f.key]} onChange={(v) => set(p.node.id, f.key, v)} varieties={project.varieties} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
