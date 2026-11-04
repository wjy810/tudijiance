import { useRef, useState } from 'react';
import { Icon } from './components/Icon';
import { exportExcel, exportJSON, importTemplate, readJSON } from './io/excel';
import { FieldMap } from './map/FieldMap';
import { Inspector } from './map/Inspector';
import { createProject } from './model/defaults';
import { projectActions, projectStore, uiStore, useStore, viewStore, type Tab } from './store/store';
import { FieldsView } from './views/FieldsView';
import { InfoView } from './views/InfoView';
import { RecordsView } from './views/RecordsView';
import { SummaryView } from './views/SummaryView';
import { VarietyView } from './views/VarietyView';
import { WeatherView } from './views/WeatherView';

const TABS: [Tab, string][] = [
  ['map', '种植图'],
  ['info', '基本情况'],
  ['varieties', '品种'],
  ['records', '田间记载'],
  ['summary', '汇总与产量'],
  ['weather', '气象资料'],
  ['fields', '字段设置'],
];

export function App() {
  const st = useStore(projectStore);
  const ui = useStore(uiStore);
  const [toast, setToast] = useState('');
  const jsonInput = useRef<HTMLInputElement>(null);
  const xlsInput = useRef<HTMLInputElement>(null);
  const menuRef = useRef<HTMLDetailsElement>(null);

  const say = (m: string) => {
    setToast(m);
    window.setTimeout(() => setToast(''), 4000);
  };
  const closeMenu = () => menuRef.current?.removeAttribute('open');
  const resetUI = () => {
    uiStore.set((s) => ({ ...s, activeId: projectStore.get().project.field.id, selection: [], anchorKey: null }));
    viewStore.set((v) => ({ ...v, ready: false }));
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <svg width="26" height="26" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="2" y="2" width="20" height="20" rx="4" fill="var(--accent)" />
            <path d="M6 7h5v4H6zM13 7h5v4h-5zM6 13h12v4H6z" fill="#fff" opacity=".9" />
          </svg>
          <div>
            <b>区试田间记载</b>
            <small title={st.project.name}>{st.project.name}</small>
          </div>
        </div>
        <nav className="tabs" role="tablist">
          {TABS.map(([k, l]) => (
            <button key={k} role="tab" aria-selected={ui.tab === k} className={ui.tab === k ? 'on' : ''} onClick={() => uiStore.set((s) => ({ ...s, tab: k }))}>
              {l}
            </button>
          ))}
        </nav>
        <div className="top-actions">
          <button className="icon-btn" onClick={projectActions.undo} disabled={!st.past.length} title="撤销 (Ctrl+Z)" aria-label="撤销">
            <Icon name="undo" />
          </button>
          <button className="icon-btn" onClick={projectActions.redo} disabled={!st.future.length} title="重做 (Ctrl+Y)" aria-label="重做">
            <Icon name="redo" />
          </button>
          <span className="saved" title="数据自动保存在本机浏览器">
            {st.savedAt ? '已自动保存' : '本地存储'}
          </span>
          {ui.tab === 'map' && (
            <button
              className={`icon-btn${ui.panelOpen ? ' on' : ''}`}
              onClick={() => uiStore.set((s) => ({ ...s, panelOpen: !s.panelOpen }))}
              title="显示/隐藏属性面板"
              aria-label="属性面板"
            >
              <Icon name="panel" />
            </button>
          )}
          <details className="menu" ref={menuRef}>
            <summary className="btn">文件 ▾</summary>
            <div className="menu-pop">
              <button
                onClick={async () => {
                  closeMenu();
                  try {
                    await exportExcel(projectStore.get().project);
                  } catch (e) {
                    say(`导出失败：${(e as Error).message}`);
                  }
                }}
              >
                <Icon name="download" /> 导出 Excel 记载本
              </button>
              <button
                onClick={() => {
                  closeMenu();
                  exportJSON(projectStore.get().project);
                }}
              >
                <Icon name="download" /> 导出项目文件 (.json)
              </button>
              <hr />
              <button
                onClick={() => {
                  closeMenu();
                  jsonInput.current?.click();
                }}
              >
                <Icon name="upload" /> 打开项目文件 (.json)
              </button>
              <button
                onClick={() => {
                  closeMenu();
                  xlsInput.current?.click();
                }}
              >
                <Icon name="upload" /> 导入记载本 (.xls/.xlsx)
              </button>
              <hr />
              <button
                className="danger"
                onClick={() => {
                  closeMenu();
                  if (!confirm('新建项目将替换当前数据（可撤销），建议先导出备份。继续？')) return;
                  projectActions.load(createProject());
                  resetUI();
                }}
              >
                新建示例项目
              </button>
            </div>
          </details>
        </div>
        <input
          ref={jsonInput}
          type="file"
          accept=".json,application/json"
          hidden
          onChange={async (e) => {
            const f = e.target.files?.[0];
            e.target.value = '';
            if (!f) return;
            try {
              projectActions.load(await readJSON(f));
              resetUI();
              say('已打开项目');
            } catch (err) {
              say(`打开失败：${(err as Error).message}`);
            }
          }}
        />
        <input
          ref={xlsInput}
          type="file"
          accept=".xls,.xlsx"
          hidden
          onChange={async (e) => {
            const f = e.target.files?.[0];
            e.target.value = '';
            if (!f) return;
            try {
              const r = await importTemplate(f, projectStore.get().project);
              projectActions.load(r.project);
              resetUI();
              say(`导入完成：${r.notes.join('；') || '未识别到数据'}`);
            } catch (err) {
              say(`导入失败：${(err as Error).message}`);
            }
          }}
        />
      </header>
      <main>
        {ui.tab === 'map' && (
          <div className={`map-page${ui.panelOpen ? '' : ' no-panel'}`}>
            <FieldMap />
            {ui.panelOpen && <Inspector />}
          </div>
        )}
        {ui.tab === 'info' && <InfoView />}
        {ui.tab === 'varieties' && <VarietyView />}
        {ui.tab === 'records' && <RecordsView />}
        {ui.tab === 'summary' && <SummaryView />}
        {ui.tab === 'weather' && <WeatherView />}
        {ui.tab === 'fields' && <FieldsView />}
      </main>
      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </div>
  );
}
