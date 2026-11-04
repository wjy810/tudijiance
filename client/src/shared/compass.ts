// 方位：平面图上方顺时针转 northDeg 度是北（地块的真北方向），画面又转了 rotation 度。
// 这里算屏幕上北朝哪、四边各是什么方向、鼠标位置离地块中心往东往北各多远

/** 角度规整到 [0, 360) */
export function normDeg(d: number): number {
  return ((d % 360) + 360) % 360
}

const NAMES = ['北', '东北', '东', '东南', '南', '西南', '西', '西北']

/** 方位角（从北顺时针）→ 八个方向里最近的一个 */
export function bearingLabel(bearing: number): string {
  return NAMES[Math.round(normDeg(bearing) / 45) % 8]!
}

/** 北在屏幕上的方向：从屏幕上方顺时针多少度 */
export function northOnScreen(northDeg: number, rotation: number): number {
  return normDeg(northDeg + rotation)
}

/** 屏幕上下左右四边各朝哪个方向 */
export function edgeLabels(northDeg: number, rotation: number): { top: string; right: string; bottom: string; left: string } {
  const n = northOnScreen(northDeg, rotation)
  // 屏幕上方的方位角 = −n
  return {
    top: bearingLabel(-n),
    right: bearingLabel(90 - n),
    bottom: bearingLabel(180 - n),
    left: bearingLabel(270 - n),
  }
}

/** 让北朝上要把画面转到多少度 */
export function northUpRotation(northDeg: number): number {
  return normDeg(-northDeg)
}

/**
 * 布局里一点相对地块中心往东、往北各多少米（布局 x 向右、y 向下，单位 × meters 换成米）
 */
export function eastNorth(p: { x: number; y: number }, center: { x: number; y: number }, northDeg: number, meters: number):
{ east: number; north: number } {
  const dx = (p.x - center.x) * meters
  const dy = (p.y - center.y) * meters
  const r = (northDeg * Math.PI) / 180
  // 北在布局里的方向 (sin r, −cos r)，东 (cos r, sin r)
  const north = dx * Math.sin(r) - dy * Math.cos(r)
  const east = dx * Math.cos(r) + dy * Math.sin(r)
  return { east, north }
}

/** "东 12.3 m · 北 4.5 m" 这样的读数，负数写成西、南 */
export function eastNorthText(v: { east: number; north: number }): string {
  const f = (n: number) => Math.abs(n) < 0.05 ? '0' : (Math.round(Math.abs(n) * 10) / 10).toString()
  return `${v.east >= 0 ? '东' : '西'} ${f(v.east)} m · ${v.north >= 0 ? '北' : '南'} ${f(v.north)} m`
}
