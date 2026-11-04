import { useState } from 'react';
import { Modal } from '../components/inputs';
import { DEFAULT_GEN, generateField, type GenOptions } from '../model/defaults';
import { projectActions, uiStore, useProject, viewStore } from '../store/store';

export function GenDialog({ onClose }: { onClose: () => void }) {
  const project = useProject();
  const n = project.varieties.length || DEFAULT_GEN.repCols;
  const [o, setO] = useState<GenOptions>(() => ({
    ...DEFAULT_GEN,
    reps: Number(project.meta.reps) || 3,
    repRows: 1,
    repCols: n,
    plotW: Number(project.meta.plotWidth) || DEFAULT_GEN.plotW,
    plotL: Number(project.meta.plotLength) || DEFAULT_GEN.plotL,
  }));
  const set = <K extends keyof GenOptions>(k: K, v: GenOptions[K]) => setO((x) => ({ ...x, [k]: v }));
  const num = (k: keyof GenOptions, label: string, unit?: string, step = 1) => (
    <label>
      <span>{label}</span>
      <span className="num-input">
        <input type="number" step={step} min={0} value={o[k] as number} onChange={(e) => set(k, Number(e.target.value) as never)} />
        {unit && <em>{unit}</em>}
      </span>
    </label>
  );
  const perRep = o.repRows * o.repCols;
  const apply = () => {
    if (!confirm('将用新布局替换当前种植图（小区数据会清空，可撤销），继续？')) return;
    projectActions.update((d) => {
      d.field = generateField(o, d.varieties);
      d.meta.reps = String(o.reps);
      d.meta.plotWidth = String(o.plotW);
      d.meta.plotLength = String(o.plotL);
    });
    const p = projectActions.get();
    uiStore.set((s) => ({ ...s, activeId: p.field.id, selection: [], anchorKey: null }));
    viewStore.set((v) => ({ ...v, ready: false, locked: false }));
    onClose();
  };
  return (
    <Modal
      title="生成田间种植图"
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>取消</button>
          <button className="primary" onClick={apply} disabled={o.reps < 1 || perRep < 1 || o.plotW <= 0 || o.plotL <= 0}>
            生成
          </button>
        </>
      }
    >
      <div className="grid2">
        {num('reps', '重复次数')}
        <label>
          <span>重复排列</span>
          <select value={o.direction} onChange={(e) => set('direction', e.target.value as 'h' | 'v')}>
            <option value="v">上下排列</option>
            <option value="h">左右并排</option>
          </select>
        </label>
        {num('repRows', '每重复小区行数')}
        {num('repCols', '每重复小区列数')}
        {num('plotW', '小区宽', 'm', 0.01)}
        {num('plotL', '小区长', 'm', 0.01)}
        {num('alley', '重复间走道', 'm', 0.1)}
        {num('protect', '四周保护区', 'm', 0.1)}
        {num('numberBase', '编号基数', '', 100)}
        <label>
          <span>品种分配</span>
          <select value={o.assign} onChange={(e) => set('assign', e.target.value as GenOptions['assign'])}>
            <option value="random">每重复随机排列</option>
            <option value="seq">顺序排列</option>
            <option value="none">不分配</option>
          </select>
        </label>
      </div>
      <p className="muted small">
        每重复 {perRep} 个小区，共 {perRep * o.reps} 个；品种 {project.varieties.length} 个
        {project.varieties.length && perRep !== project.varieties.length ? '（小区数与品种数不一致，将循环分配）' : ''}。 小区编号 = 重复号 ×{' '}
        {o.numberBase} + 序号，如 {o.numberBase + 1}、{o.numberBase * 2 + 1}。
      </p>
    </Modal>
  );
}
