import { useEffect, useState, type ReactNode } from 'react';
import { parseOption } from '../model/fields';
import type { FieldDef, Variety } from '../model/types';

/** 数字输入：失焦或回车时提交 */
export function NumInput({
  value,
  onCommit,
  min,
  step = 0.01,
  digits = 2,
  suffix,
  disabled,
  className,
  ariaLabel,
}: {
  value: number;
  onCommit: (v: number) => void;
  min?: number;
  step?: number;
  digits?: number;
  suffix?: string;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
}) {
  const fmt = (v: number) => (isFinite(v) ? String(Math.round(v * 10 ** digits) / 10 ** digits) : '');
  const [text, setText] = useState(fmt(value));
  useEffect(() => setText(fmt(value)), [value]); // eslint-disable-line react-hooks/exhaustive-deps
  const commit = () => {
    const n = Number(text);
    if (text.trim() === '' || !isFinite(n) || (min != null && n < min)) {
      setText(fmt(value));
      return;
    }
    if (fmt(n) !== fmt(value)) onCommit(n);
  };
  return (
    <span className={`num-input ${className ?? ''}`}>
      <input
        type="number"
        inputMode="decimal"
        step={step}
        min={min}
        value={text}
        disabled={disabled}
        aria-label={ariaLabel}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
          if (e.key === 'Escape') {
            setText(fmt(value));
            (e.target as HTMLInputElement).blur();
          }
        }}
      />
      {suffix && <em>{suffix}</em>}
    </span>
  );
}

export function FieldInput({
  def,
  value,
  onChange,
  varieties,
  compact,
}: {
  def: FieldDef;
  value: string | undefined;
  onChange: (v: string) => void;
  varieties: Variety[];
  compact?: boolean;
}) {
  const v = value ?? '';
  const title = def.hint;
  switch (def.type) {
    case 'number':
      return (
        <input type="number" inputMode="decimal" step="any" value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)} />
      );
    case 'date':
      return <input type="date" value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)} />;
    case 'select': {
      const opts = (def.options ?? []).map(parseOption);
      return (
        <select value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)}>
          <option value="">{compact ? '' : '—'}</option>
          {v && !opts.some((o) => o.value === v) && <option value={v}>{v}</option>}
          {opts.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label === o.value ? o.value : compact ? o.label : `${o.value} ${o.label}`}
            </option>
          ))}
        </select>
      );
    }
    case 'variety':
      return (
        <select value={v} aria-label={def.label} onChange={(e) => onChange(e.target.value)}>
          <option value="">{compact ? '' : '— 未指定 —'}</option>
          {varieties.map((x) => (
            <option key={x.id} value={x.id}>
              {x.code ? `${x.code} ` : ''}
              {x.name}
              {x.role === 'ck' ? '(CK)' : x.role === 'ck2' ? '(辅CK)' : ''}
            </option>
          ))}
        </select>
      );
    case 'textarea':
      return compact ? (
        <input type="text" value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)} />
      ) : (
        <textarea rows={2} value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)} />
      );
    default:
      return <input type="text" value={v} title={title} aria-label={def.label} onChange={(e) => onChange(e.target.value)} />;
  }
}

/** 分组表单 */
export function FieldForm({
  defs,
  data,
  onChange,
  varieties,
  openGroups,
}: {
  defs: FieldDef[];
  data: Record<string, string>;
  onChange: (key: string, v: string) => void;
  varieties: Variety[];
  openGroups?: string[];
}) {
  const groups: [string, FieldDef[]][] = [];
  for (const d of defs) {
    const g = d.group || '其他';
    let e = groups.find((x) => x[0] === g);
    if (!e) groups.push((e = [g, []]));
    e[1].push(d);
  }
  return (
    <div className="field-form">
      {groups.map(([g, list], i) => {
        const filled = list.filter((d) => data[d.key]).length;
        return (
          <details key={g} open={openGroups ? openGroups.includes(g) : i === 0}>
            <summary>
              {g}
              <span className="count">
                {filled}/{list.length}
              </span>
            </summary>
            <div className="ff-grid">
              {list.map((d) => (
                <label key={d.key} className={d.type === 'textarea' ? 'wide' : ''} title={d.hint}>
                  <span className="lbl">
                    {d.label}
                    {d.unit && <em>{d.unit}</em>}
                  </span>
                  <FieldInput def={d} value={data[d.key]} onChange={(v) => onChange(d.key, v)} varieties={varieties} />
                </label>
              ))}
            </div>
          </details>
        );
      })}
    </div>
  );
}

export function Section({ title, children, right, open = true }: { title: ReactNode; children: ReactNode; right?: ReactNode; open?: boolean }) {
  return (
    <details className="section" open={open}>
      <summary>
        <span>{title}</span>
        {right}
      </summary>
      <div className="section-body">{children}</div>
    </details>
  );
}

export function Modal({ title, children, onClose, footer }: { title: string; children: ReactNode; onClose: () => void; footer?: ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  return (
    <div className="modal-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <h2>{title}</h2>
          <button className="ghost" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer>{footer}</footer>}
      </div>
    </div>
  );
}
