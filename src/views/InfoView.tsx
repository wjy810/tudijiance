import { FieldForm } from '../components/inputs';
import { collectPlots, fmt, harvestArea } from '../model/calc';
import { projectActions, useProject } from '../store/store';

export function InfoView() {
  const project = useProject();
  const area = harvestArea(project.meta);
  const plots = collectPlots(project);
  const groups = [...new Set(project.schemas.meta.map((f) => f.group || '其他'))];
  return (
    <div className="page">
      <div className="page-head">
        <h1>基本情况</h1>
        <p className="muted">对应记载本“封面”“基本情况”及产量表中的小区参数。</p>
      </div>
      <div className="kpis">
        <div>
          <b>{project.varieties.length}</b>
          <span>供试品种数</span>
        </div>
        <div>
          <b>{project.meta.reps || '—'}</b>
          <span>重复次数</span>
        </div>
        <div>
          <b>{plots.length}</b>
          <span>小区数</span>
        </div>
        <div>
          <b>{area ? fmt(area, 2) : '—'}</b>
          <span>收获面积 m²</span>
        </div>
      </div>
      <label className="row-field wide-input">
        <span>项目名称</span>
        <input value={project.name} onChange={(e) => projectActions.update((d) => void (d.name = e.target.value), { coalesce: 'pname' })} />
      </label>
      <div className="card">
        <FieldForm
          defs={project.schemas.meta}
          data={project.meta}
          varieties={project.varieties}
          openGroups={groups}
          onChange={(k, v) => projectActions.update((d) => void (d.meta[k] = v), { coalesce: `meta:${k}` })}
        />
      </div>
    </div>
  );
}
