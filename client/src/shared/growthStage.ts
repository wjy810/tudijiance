// 小麦生育期进度：把后端给的"当前生育期"（最近记录过的物候期项目名）对到 8 个主要时期上。
// 记载标准里还有分蘖、起身、孕穗、灌浆这些中间时期，它们归到前一个主要时期里显示。

export interface MainStage {
  key: string
  /** 进度条上显示的短名 */
  label: string
  /** 归到这个时期的物候期项目名（和录入项目名称一致） */
  names: string[]
  /** 大概在几月（只做提示，各地不同） */
  months: string
}

export const MAIN_STAGES: MainStage[] = [
  { key: 'sowing', label: '播种', names: ['播种期'], months: '10 月' },
  { key: 'emergence', label: '出苗', names: ['出苗期', '分蘖期'], months: '10–11 月' },
  { key: 'wintering', label: '越冬', names: ['越冬期'], months: '12–1 月' },
  { key: 'regreening', label: '返青', names: ['返青期', '起身期'], months: '2–3 月' },
  { key: 'jointing', label: '拔节', names: ['拔节期', '孕穗期'], months: '3–4 月' },
  { key: 'heading', label: '抽穗', names: ['抽穗期'], months: '4 月' },
  { key: 'flowering', label: '扬花', names: ['扬花期', '灌浆期'], months: '4–5 月' },
  { key: 'maturity', label: '成熟', names: ['成熟期'], months: '5–6 月' },
]

function normalize(name: string): string {
  return name.replace(/\s/g, '').replace(/期$/, '')
}

/**
 * 当前生育期在 8 个主要时期里排第几（从 0 开始）；没记录过或认不出来返回 -1。
 * 认法：先按项目名精确对，再去掉"期"字对（"抽穗"也能认）。
 */
export function stageIndex(current: string | null | undefined): number {
  if (!current) {
    return -1
  }
  const exact = MAIN_STAGES.findIndex((s) => s.names.includes(current.trim()))
  if (exact >= 0) {
    return exact
  }
  const n = normalize(current)
  return MAIN_STAGES.findIndex((s) => s.names.some((x) => normalize(x) === n) || s.label === n)
}

export interface StageView {
  index: number
  /** 当前时期的完整说法，例如"分蘖期（出苗之后）"；没有记录时是 null */
  caption: string | null
}

/** 进度条下方那句话 */
export function stageView(current: string | null | undefined): StageView {
  const index = stageIndex(current)
  if (!current || index < 0) {
    return { index, caption: current ? `${current}` : null }
  }
  const main = MAIN_STAGES[index]!
  const isMain = normalize(current) === main.label
  return { index, caption: isMain ? current : `${current}（${main.label}之后）` }
}
