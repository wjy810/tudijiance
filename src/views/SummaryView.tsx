import { useMemo, useState } from 'react';
import { fmt, traitSummary, yieldTable } from '../model/calc';
import { roman } from '../model/defaults';
import { displayValue } from '../model/display';
import { useProject } from '../store/store';

export function SummaryView() {
  const project = useProject();
  const [base, setBase] = useState<'mean' | 'ck'>('mean');
  const yt = useMemo(() => yieldTable(project, base), [project, base]);
  const defs = project.schemas.plot.filter((f) => !['plotNo', 'variety', 'plotYield', 'fieldEval'].includes(f.key) && f.type !== 'textarea');
  const groups = [...new Set(defs.map((f) => f.group || '其他'))];
  const [group, setGroup] = useState(groups[0] ?? '');
  const cols = defs.filter((f) => (f.group || '其他') === group);
  const traits = useMemo(() => traitSummary(project, defs), [project]); // eslint-disable-line react-hooks/exhaustive-deps
  const vmap = new Map(project.varieties.map((v) => [v.id, v]));

  return (
    <div className="page wide">
      <div className="page-head">
        <h1>九、产量表</h1>
        <p className="muted">
          折亩产 = 小区产量 ÷ 收获面积 × 666.67；收获面积 {yt.area ? `${fmt(yt.area)} m²` : <b className="warn">未设置（请在“基本情况”填写小区参数）</b>}。
        </p>
      </div>
      <div className="filters">
        <div className="seg-group">
          <button className={base === 'mean' ? 'on' : ''} onClick={() => setBase('mean')}>
            比试验平均
          </button>
          <button className={base === 'ck' ? 'on' : ''} onClick={() => setBase('ck')}>
            比共同对照
          </button>
        </div>
        <span className="muted small">基准亩产：{yt.baseValue != null ? `${fmt(yt.baseValue, 1)} kg` : '—'}</span>
      </div>
      <div className="table-scroll">
        <table className="tbl num">
          <thead>
            <tr>
              <th rowSpan={2}>代号</th>
              <th rowSpan={2}>品种名称</th>
              <th colSpan={yt.reps.length + 1}>小区产量（kg）</th>
              <th colSpan={yt.reps.length + 1}>折亩产（kg）</th>
              <th rowSpan={2}>±%</th>
              <th rowSpan={2}>位次</th>
            </tr>
            <tr>
              {yt.reps.map((r) => (
                <th key={`a${r}`}>{roman(r)}</th>
              ))}
              <th>平均</th>
              {yt.reps.map((r) => (
                <th key={`b${r}`}>{roman(r)}</th>
              ))}
              <th>平均</th>
            </tr>
          </thead>
          <tbody>
            {yt.rows.map((r) => (
              <tr key={r.variety.id} className={r.variety.role === 'ck' ? 'ck' : ''}>
                <td>{r.variety.code}</td>
                <td className="txt">
                  {r.variety.name}
                  {r.variety.role === 'ck' && <span className="badge">CK</span>}
                </td>
                {r.plot.map((v, i) => (
                  <td key={`p${i}`}>{fmt(v)}</td>
                ))}
                <td>
                  <b>{fmt(r.plotMean)}</b>
                </td>
                {r.mu.map((v, i) => (
                  <td key={`m${i}`}>{fmt(v, 1)}</td>
                ))}
                <td>
                  <b>{fmt(r.muMean, 1)}</b>
                </td>
                <td className={r.diff != null ? (r.diff >= 0 ? 'pos' : 'neg') : ''}>{r.diff != null ? `${r.diff >= 0 ? '+' : ''}${fmt(r.diff)}` : ''}</td>
                <td>{r.rank ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="page-head" style={{ marginTop: 28 }}>
        <h1>五、性状汇总 / 六、室内考种</h1>
        <p className="muted">按品种汇总各重复：数值取平均，等级/文本取众数，日期取平均（月/日）。</p>
      </div>
      <div className="filters">
        <div className="seg-group">
          {groups.map((g) => (
            <button key={g} className={group === g ? 'on' : ''} onClick={() => setGroup(g)}>
              {g}
            </button>
          ))}
        </div>
      </div>
      <div className="table-scroll">
        <table className="tbl num">
          <thead>
            <tr>
              <th>代号</th>
              <th>品种名称</th>
              {group === '生育期' && <th>全生育期(天)</th>}
              {cols.map((f) => (
                <th key={f.key}>
                  {f.label}
                  {f.unit && <em>{f.unit}</em>}
                </th>
              ))}
              {group === '茎蘖与株高' && <th>成穗率%</th>}
            </tr>
          </thead>
          <tbody>
            {traits.map((t) => (
              <tr key={t.variety.id} className={t.variety.role === 'ck' ? 'ck' : ''}>
                <td>{t.variety.code}</td>
                <td className="txt">{t.variety.name}</td>
                {group === '生育期' && <td>{t.growthDays}</td>}
                {cols.map((f) => (
                  <td key={f.key}>{f.type === 'select' ? displayValue(f, t.values[f.key], vmap) : t.values[f.key]}</td>
                ))}
                {group === '茎蘖与株高' && <td>{t.spikeRate}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
