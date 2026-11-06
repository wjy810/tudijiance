// 地块：属于某一季的实验田。各处选地块时带上季名（"2025-2026 年度大田 · 东地"），最新一季的排在前面
import type { Field, Season, SeasonView } from '@/shared/api'

export function seasonMap(views: SeasonView[]): Map<number, Season> {
  return new Map(views.map((v) => [v.season.id, v.season]))
}

/** 显示用的名字：季里的地块带上季名 */
export function landLabel(f: Field, seasons: Map<number, Season>): string {
  const s = f.seasonId ? seasons.get(f.seasonId) : undefined
  return s ? `${s.name} · ${f.name}` : f.name
}

/** 最新一季的地块在前；同一季里按建的先后 */
export function sortLands(fields: Field[], seasons: Map<number, Season>): Field[] {
  const start = (f: Field) => (f.seasonId ? seasons.get(f.seasonId)?.startDate ?? '' : '')
  return [...fields].sort((a, b) => start(b).localeCompare(start(a)) || a.id - b.id)
}

/** 现在是哪一季：今天在起止日期里的（有几季时取开始最晚的），没有就取开始最晚的一季 */
export function currentSeason(seasons: Season[], today = new Date()): Season | null {
  const d = today.toISOString().slice(0, 10)
  const ordered = [...seasons].sort((a, b) => b.startDate.localeCompare(a.startDate) || b.id - a.id)
  return ordered.find((s) => s.startDate <= d && (!s.endDate || s.endDate >= d))
    ?? ordered.find((s) => s.startDate <= d)
    ?? ordered[0]
    ?? null
}

/**
 * 同名地块只留最新一季的那块：看历年变化、自助分析、大屏时，各季的"东地"连起来算一块地
 */
export function latestPerName(fields: Field[], seasons: Map<number, Season>): Field[] {
  const out = new Map<string, Field>()
  for (const f of sortLands(fields, seasons)) {
    if (!out.has(f.name)) out.set(f.name, f)
  }
  return [...out.values()]
}
