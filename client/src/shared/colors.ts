// 不依赖图表库的配色工具，平面图等页面也能用

/**
 * 顺序色阶（米黄 → 田野绿），用于按数值大小给小区着色。
 * 最深一级也保证格子里的深色文字看得清。
 */
const SEQUENTIAL = ['#f4eedc', '#e1e7c4', '#c3d9a6', '#9cc58f', '#77ad7c']

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

/**
 * 平面图上区分品种用的大地色（鼠尾草绿、麦黄、杏色、天青……），都够浅，深色文字看得清。
 * 按品种编号排序后依次分配。
 */
export const VARIETY_COLORS = ['#cfe2c2', '#f1d9a2', '#efc3aa', '#c5dbe4', '#dccfe6', '#ead0cf', '#bfdcd0',
  '#e8dcb2', '#d9c7a6', '#cbd8b0', '#f3ccA6', '#d8d2c3']

/** 重复用的颜色：一个重复一种，彼此差别大 */
export const REPLICATE_COLORS = ['#c5dbe4', '#cfe2c2', '#f1d9a2', '#dccfe6', '#efc3aa', '#ead0cf']
