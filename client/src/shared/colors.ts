// 不依赖图表库的配色工具，平面图等页面也能用
/**
 * 顺序色阶（单一蓝色，从浅到深），用于按数值大小给小区着色。
 * 只用到中间偏浅的几级，保证格子里的深色文字看得清。
 */
const SEQUENTIAL = ['#e6f0fc', '#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5']

export function sequentialColor(t: number): string {
  const x = Math.min(1, Math.max(0, Number.isFinite(t) ? t : 0)) * (SEQUENTIAL.length - 1)
  const i = Math.min(Math.floor(x), SEQUENTIAL.length - 2)
  const f = x - i
  const a = SEQUENTIAL[i]!
  const b = SEQUENTIAL[i + 1]!
  const ch = (hex: string, k: number) => parseInt(hex.slice(1 + k * 2, 3 + k * 2), 16)
  const mix = (k: number) => Math.round(ch(a, k) + (ch(b, k) - ch(a, k)) * f).toString(16).padStart(2, '0')
  return `#${mix(0)}${mix(1)}${mix(2)}`
}

export const SEQUENTIAL_GRADIENT = `linear-gradient(90deg, ${SEQUENTIAL.join(', ')})`
