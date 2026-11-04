import { WEATHER_MONTHS, WEATHER_ROWS } from '../model/fields';
import { projectActions, useProject } from '../store/store';

export function WeatherView() {
  const project = useProject();
  const year = project.meta.year || '';
  const set = (k: string, v: string) => projectActions.update((d) => void (d.weather[k] = v), { coalesce: `w:${k}` });
  let lastGroup = '';
  return (
    <div className="page wide">
      <div className="page-head">
        <h1>三、{year}年度小麦生育期间气象资料表</h1>
        <p className="muted">当年 / 常年对比，单位见行标题。</p>
      </div>
      <div className="table-scroll">
        <table className="tbl weather">
          <thead>
            <tr>
              <th rowSpan={2} colSpan={2} />
              {WEATHER_MONTHS.map((m) => (
                <th key={m} colSpan={2}>
                  {m}月
                </th>
              ))}
            </tr>
            <tr>
              {WEATHER_MONTHS.flatMap((m) => [
                <th key={`${m}c`}>当年</th>,
                <th key={`${m}n`}>常年</th>,
              ])}
            </tr>
          </thead>
          <tbody>
            {WEATHER_ROWS.map((r) => {
              const showGroup = r.group !== lastGroup;
              lastGroup = r.group;
              const span = WEATHER_ROWS.filter((x) => x.group === r.group).length;
              return (
                <tr key={r.key}>
                  {showGroup && (
                    <th rowSpan={span} className="grp">
                      {r.group}
                    </th>
                  )}
                  <th className="rl">{r.label}</th>
                  {WEATHER_MONTHS.flatMap((m) =>
                    (['cur', 'norm'] as const).map((t) => {
                      const k = `${r.key}|${m}|${t}`;
                      return (
                        <td key={k} className={t}>
                          <input type="number" step="any" inputMode="decimal" value={project.weather[k] ?? ''} onChange={(e) => set(k, e.target.value)} aria-label={`${m}月${r.label}${t === 'cur' ? '当年' : '常年'}`} />
                        </td>
                      );
                    }),
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
