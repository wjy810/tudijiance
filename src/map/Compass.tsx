const DIRS: [string, string][] = [
  ['北', 'N'],
  ['东', 'E'],
  ['南', 'S'],
  ['西', 'W'],
];

/** 指北针：angle 为屏幕上北方相对屏幕上方的顺时针角度 */
export function Compass({ angle, rot, locked, onClick }: { angle: number; rot: number; locked: boolean; onClick: () => void }) {
  const ticks = Array.from({ length: 24 }, (_, i) => i * 15);
  return (
    <div className="compass-box">
      <button className="compass" onClick={onClick} disabled={locked} title={locked ? '视角已锁定' : '点击：正北朝上'} aria-label="指北针">
        <svg viewBox="-50 -50 100 100" width={104} height={104}>
          <circle r={47} className="c-ring" />
          <g transform={`rotate(${angle})`}>
            {ticks.map((t) => (
              <line
                key={t}
                x1={0}
                y1={-47}
                x2={0}
                y2={t % 90 === 0 ? -40 : -43.5}
                transform={`rotate(${t})`}
                className={t % 90 === 0 ? 'c-tick major' : 'c-tick'}
              />
            ))}
            <polygon points="0,-34 6.5,0 -6.5,0" className="c-north" />
            <polygon points="0,34 6.5,0 -6.5,0" className="c-south" />
            <circle r={3.2} className="c-hub" />
            {DIRS.map(([zh, en], i) => (
              <g key={zh} transform={`rotate(${i * 90}) translate(0 -27) rotate(${-(angle + i * 90)})`}>
                <text className={`c-label${i === 0 ? ' n' : ''}`} y={-0.5}>
                  {zh}
                </text>
                <text className="c-en" y={8}>
                  {en}
                </text>
              </g>
            ))}
          </g>
        </svg>
      </button>
      <div className="compass-info">
        视角 {Math.round(((rot % 360) + 360) % 360)}° {locked && <span className="badge">锁定</span>}
      </div>
    </div>
  );
}

const NICE = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500];

export function ScaleBar({ zoom }: { zoom: number }) {
  let L = NICE[0];
  for (const n of NICE) if (n * zoom <= 140) L = n;
  const w = L * zoom;
  return (
    <div className="scalebar" aria-label="比例尺">
      <div className="bar" style={{ width: w }} />
      <span>{L} m</span>
    </div>
  );
}
