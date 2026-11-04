import { useState } from 'react';
import { varietyColor } from '../model/display';
import { uid } from '../model/grid';
import type { Variety, VarietyRole } from '../model/types';
import { projectActions, useProject } from '../store/store';

const ROLES: [VarietyRole, string][] = [
  ['test', '参试'],
  ['ck', '共同对照'],
  ['ck2', '辅助对照'],
];

export function VarietyView() {
  const project = useProject();
  const [bulk, setBulk] = useState('');
  const upd = (i: number, patch: Partial<Variety>, key?: string) =>
    projectActions.update(
      (d) => {
        Object.assign(d.varieties[i], patch);
        if (patch.role === 'ck') d.meta.commonCK = d.varieties[i].id;
      },
      key ? { coalesce: `v:${d_id(project.varieties[i])}:${key}` } : {},
    );
  const add = () =>
    projectActions.update((d) => {
      d.varieties.push({ id: uid('v'), code: String(d.varieties.length + 1), name: '', role: 'test' });
    });
  const addBulk = () => {
    const lines = bulk
      .split(/\n/)
      .map((l) => l.trim())
      .filter(Boolean);
    if (!lines.length) return;
    projectActions.update((d) => {
      for (const l of lines) {
        const parts = l.split(/[\t,，\s]+/).filter(Boolean);
        const [code, name] = parts.length > 1 ? [parts[0], parts.slice(1).join(' ')] : [String(d.varieties.length + 1), parts[0]];
        d.varieties.push({ id: uid('v'), code, name, role: /CK|对照/i.test(l) ? 'ck' : 'test' });
      }
    });
    setBulk('');
  };
  const move = (i: number, di: number) =>
    projectActions.update((d) => {
      const j = i + di;
      if (j < 0 || j >= d.varieties.length) return;
      [d.varieties[i], d.varieties[j]] = [d.varieties[j], d.varieties[i]];
    });
  const del = (i: number) => {
    if (!confirm(`删除品种“${project.varieties[i].name}”？已分配到小区的引用将清空。`)) return;
    projectActions.update((d) => {
      const id = d.varieties[i].id;
      d.varieties.splice(i, 1);
      const clear = (n: typeof d.field) => {
        if (n.data.variety === id) n.data.variety = '';
        Object.values(n.children).forEach(clear);
      };
      clear(d.field);
    });
  };

  return (
    <div className="page">
      <div className="page-head">
        <h1>参试品种</h1>
        <p className="muted">品种代号/名称用于种植图、田间记载、考种和产量表；颜色即种植图中的品种色。</p>
      </div>
      <div className="card">
        <table className="tbl">
          <thead>
            <tr>
              <th style={{ width: 36 }} />
              <th style={{ width: 90 }}>代号</th>
              <th>品种名称</th>
              <th style={{ width: 120 }}>类别</th>
              <th>选育/供种单位</th>
              <th>备注</th>
              <th style={{ width: 110 }} />
            </tr>
          </thead>
          <tbody>
            {project.varieties.map((v, i) => (
              <tr key={v.id}>
                <td>
                  <span className="swatch" style={{ background: varietyColor(i, v.role) }} />
                </td>
                <td>
                  <input value={v.code} onChange={(e) => upd(i, { code: e.target.value }, 'code')} aria-label="代号" />
                </td>
                <td>
                  <input value={v.name} onChange={(e) => upd(i, { name: e.target.value }, 'name')} aria-label="品种名称" />
                </td>
                <td>
                  <select value={v.role} onChange={(e) => upd(i, { role: e.target.value as VarietyRole })} aria-label="类别">
                    {ROLES.map(([k, l]) => (
                      <option key={k} value={k}>
                        {l}
                      </option>
                    ))}
                  </select>
                </td>
                <td>
                  <input value={v.source ?? ''} onChange={(e) => upd(i, { source: e.target.value }, 'source')} aria-label="单位" />
                </td>
                <td>
                  <input value={v.note ?? ''} onChange={(e) => upd(i, { note: e.target.value }, 'note')} aria-label="备注" />
                </td>
                <td className="actions">
                  <button className="mini" onClick={() => move(i, -1)} title="上移">
                    ↑
                  </button>
                  <button className="mini" onClick={() => move(i, 1)} title="下移">
                    ↓
                  </button>
                  <button className="mini danger" onClick={() => del(i)} title="删除">
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="btn-row" style={{ marginTop: 10 }}>
          <button className="primary" onClick={add}>
            + 添加品种
          </button>
        </div>
      </div>
      <div className="card">
        <h3>批量添加</h3>
        <p className="muted small">每行一个品种，格式“代号 名称”（可直接从 Excel 粘贴两列）；含“CK/对照”字样的标记为对照。</p>
        <textarea rows={4} value={bulk} onChange={(e) => setBulk(e.target.value)} placeholder={'13 新品种A\n14 新品种B'} />
        <div className="btn-row">
          <button onClick={addBulk} disabled={!bulk.trim()}>
            添加
          </button>
        </div>
      </div>
      <div className="card">
        <h3>十、品种评价</h3>
        <label className="stack">
          <span>气象分析</span>
          <textarea
            rows={3}
            value={project.weatherAnalysis}
            onChange={(e) => projectActions.update((d) => void (d.weatherAnalysis = e.target.value), { coalesce: 'wa' })}
          />
        </label>
        {project.varieties.map((v, i) => (
          <label key={v.id} className="stack">
            <span>
              {v.code} {v.name}
            </span>
            <textarea rows={2} value={v.evaluation ?? ''} onChange={(e) => upd(i, { evaluation: e.target.value }, 'eval')} />
          </label>
        ))}
      </div>
    </div>
  );
}

const d_id = (v: Variety | undefined) => v?.id ?? '';
