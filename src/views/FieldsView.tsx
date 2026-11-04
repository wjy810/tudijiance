import { useState } from 'react';
import { DEFAULT_SCHEMAS, LEVEL_LABELS } from '../model/fields';
import type { FieldDef, FieldType, SchemaLevel } from '../model/types';
import { projectActions, useProject } from '../store/store';

const TYPES: [FieldType, string][] = [
  ['text', '文本'],
  ['number', '数字'],
  ['date', '日期'],
  ['select', '选项'],
  ['textarea', '长文本'],
  ['variety', '品种'],
];

const LEVELS: SchemaLevel[] = ['plot', 'block', 'sub', 'meta'];

/** 字段设置：每个层级（区块/小区/子块/基本情况）的字段均可增删改 */
export function FieldsView() {
  const project = useProject();
  const [level, setLevel] = useState<SchemaLevel>('plot');
  const list = project.schemas[level];
  const upd = (fn: (l: FieldDef[]) => void, coalesce?: string) =>
    projectActions.update((d) => fn(d.schemas[level]), coalesce ? { coalesce } : {});

  const add = () =>
    upd((l) => {
      let i = 1;
      while (l.some((f) => f.key === `custom${i}`)) i++;
      l.push({ key: `custom${i}`, label: `自定义字段${i}`, type: 'text', group: l[l.length - 1]?.group ?? '其他' });
    });

  return (
    <div className="page wide">
      <div className="page-head">
        <h1>字段设置</h1>
        <p className="muted">
          按层级定义要记录的字段。“在图上显示”的字段会作为种植图标签。内置字段（锁）被产量计算/导出使用，可改名称但不能删除。选项格式：每行一个，“值|说明”。
        </p>
      </div>
      <div className="filters">
        <div className="seg-group">
          {LEVELS.map((l) => (
            <button key={l} className={level === l ? 'on' : ''} onClick={() => setLevel(l)}>
              {LEVEL_LABELS[l]}
              <small> {project.schemas[l].length}</small>
            </button>
          ))}
        </div>
        <button onClick={add} className="primary">
          + 添加字段
        </button>
        <button
          onClick={() => {
            if (confirm(`恢复“${LEVEL_LABELS[level]}”为记载本默认字段？自定义字段将被移除（已填数据保留在小区中）。`))
              projectActions.update((d) => void (d.schemas[level] = JSON.parse(JSON.stringify(DEFAULT_SCHEMAS[level]))));
          }}
        >
          恢复默认
        </button>
      </div>
      <div className="table-scroll">
        <table className="tbl fields">
          <thead>
            <tr>
              <th style={{ width: 60 }}>顺序</th>
              <th>名称</th>
              <th>分组</th>
              <th style={{ width: 96 }}>类型</th>
              <th style={{ width: 90 }}>单位</th>
              <th>选项 / 说明</th>
              <th style={{ width: 70 }}>图上显示</th>
              <th style={{ width: 120 }}>键</th>
              <th style={{ width: 40 }} />
            </tr>
          </thead>
          <tbody>
            {list.map((f, i) => (
              <tr key={f.key}>
                <td className="actions">
                  <button className="mini" disabled={i === 0} onClick={() => upd((l) => void ([l[i - 1], l[i]] = [l[i], l[i - 1]]))} aria-label="上移">
                    ↑
                  </button>
                  <button
                    className="mini"
                    disabled={i === list.length - 1}
                    onClick={() => upd((l) => void ([l[i + 1], l[i]] = [l[i], l[i + 1]]))}
                    aria-label="下移"
                  >
                    ↓
                  </button>
                </td>
                <td>
                  <input value={f.label} onChange={(e) => upd((l) => void (l[i].label = e.target.value), `fl:${level}:${f.key}`)} aria-label="名称" />
                </td>
                <td>
                  <input value={f.group ?? ''} onChange={(e) => upd((l) => void (l[i].group = e.target.value), `fg:${level}:${f.key}`)} aria-label="分组" />
                </td>
                <td>
                  <select value={f.type} disabled={f.system} onChange={(e) => upd((l) => void (l[i].type = e.target.value as FieldType))} aria-label="类型">
                    {TYPES.map(([k, l]) => (
                      <option key={k} value={k}>
                        {l}
                      </option>
                    ))}
                  </select>
                </td>
                <td>
                  <input value={f.unit ?? ''} onChange={(e) => upd((l) => void (l[i].unit = e.target.value), `fu:${level}:${f.key}`)} aria-label="单位" />
                </td>
                <td>
                  {f.type === 'select' ? (
                    <textarea
                      rows={Math.min(4, Math.max(2, f.options?.length ?? 2))}
                      value={(f.options ?? []).join('\n')}
                      onChange={(e) => upd((l) => void (l[i].options = e.target.value.split('\n')), `fo:${level}:${f.key}`)}
                      aria-label="选项"
                    />
                  ) : (
                    <input
                      value={f.hint ?? ''}
                      placeholder="说明（可选）"
                      onChange={(e) => upd((l) => void (l[i].hint = e.target.value), `fh:${level}:${f.key}`)}
                      aria-label="说明"
                    />
                  )}
                </td>
                <td style={{ textAlign: 'center' }}>
                  <input type="checkbox" checked={!!f.onMap} onChange={(e) => upd((l) => void (l[i].onMap = e.target.checked))} aria-label="图上显示" />
                </td>
                <td>
                  <code>{f.key}</code>
                </td>
                <td>
                  {f.system ? (
                    <span title="内置字段" aria-label="内置字段">
                      🔒
                    </span>
                  ) : (
                    <button
                      className="mini danger"
                      onClick={() => confirm(`删除字段“${f.label}”？`) && upd((l) => void l.splice(i, 1))}
                      aria-label="删除"
                    >
                      ✕
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
