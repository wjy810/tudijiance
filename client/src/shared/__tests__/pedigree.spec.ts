import { describe, expect, it } from 'vitest'

import type { LineRow, Season } from '@/shared/api'
import {
  ancestors, childrenMap, descendants, familyMembers, latestGeneration, layoutGraph, lineLabel, naturalCompare,
} from '@/shared/pedigree'

function season(id: number, name: string, startDate: string): Season {
  return { id, name, startDate, endDate: null, remark: null, createdAt: '' }
}

function line(id: number, code: string, extra: Partial<LineRow> = {}): LineRow {
  return {
    id, name: code, code, kind: 'FOUNDER', status: 'ACTIVE', pedigree: code,
    motherId: null, fatherId: null, parentId: null, parentText: '原始材料', formerNames: null,
    originSeasonId: null, homeSeasonId: 1, planted: [], childCount: 0,
    ecotype: null, qualityType: null, breeder: null, remark: null, tagNo: null, seqNo: null,
    ...extra,
  }
}

// 第一季：周麦16、郑麦366，从周麦16 选出周麦16-1，杂交出 25-001；第二季：从 25-001 选出 25-001-3
const S1 = season(1, '2024-2025 年度大田', '2024-10-01')
const S2 = season(2, '2025-2026 年度大田', '2025-10-01')
const A = line(1, '周麦16')
const B = line(2, '郑麦366')
const A1 = line(3, '周麦16-1', { kind: 'SELECTION', parentId: 1, originSeasonId: 1, homeSeasonId: 1 })
const AB = line(4, '25-001', {
  kind: 'CROSS', motherId: 1, fatherId: 2, originSeasonId: 1, homeSeasonId: 1,
  planted: [{ seasonId: 2, generation: 1 }],
})
const AB3 = line(5, '25-001-3', { kind: 'SELECTION', parentId: 4, originSeasonId: 2, homeSeasonId: 2 })
const LOOSE = line(6, '还没种', { homeSeasonId: null })
const ALL = [A, B, A1, AB, AB3, LOOSE]

describe('pedigree', () => {
  it('编号按数字段排', () => {
    expect(naturalCompare('25-002', '25-010')).toBeLessThan(0)
    expect(naturalCompare('25-001-2', '25-001-10')).toBeLessThan(0)
    expect(naturalCompare('26-001', '25-999')).toBeGreaterThan(0)
    expect(naturalCompare('周麦16', '周麦16-1')).toBeLessThan(0)
  })

  it('祖先、后代、家系', () => {
    const byId = new Map(ALL.map((l) => [l.id, l]))
    expect([...ancestors(5, byId)].sort()).toEqual([1, 2, 4])
    expect([...descendants(1, childrenMap(ALL))].sort()).toEqual([3, 4, 5])
    // 家系只沿选株往下
    expect([...familyMembers(4, ALL)]).toEqual([5])
  })

  it('按季分列，同一季里父母在左边的小列', () => {
    const g = layoutGraph(ALL, [S2, S1])
    expect(g.columns.map((c) => c.title)).toEqual(['2024-2025 年度大田', '2025-2026 年度大田', '还没种'])
    const at = (id: number) => g.nodes.find((n) => n.line.id === id)!
    expect(at(1).col).toBe(0)
    expect(at(1).depth).toBe(0)
    expect(at(3).depth).toBe(1)
    expect(at(4).depth).toBe(1)
    expect(at(4).x).toBeGreaterThan(at(1).x)
    expect(at(5).col).toBe(1)
    expect(at(6).col).toBe(2)
    // 杂交组合两条线：母本、父本
    expect(g.edges.filter((e) => e.to === 4).map((e) => e.role).sort()).toEqual(['father', 'mother'])
    // 同一小列里不重叠
    const ys = g.nodes.filter((n) => n.col === 0 && n.depth === 0).map((n) => n.y)
    expect(new Set(ys).size).toBe(ys.length)
  })

  it('折叠、只看某几季、只画家系、淘汰的默认不画', () => {
    const collapsed = layoutGraph(ALL, [S1, S2], { collapsed: new Set([4]) })
    expect(collapsed.nodes.some((n) => n.line.id === 5)).toBe(false)
    expect(collapsed.nodes.find((n) => n.line.id === 4)!.hidden).toBe(1)
    const onlyS2 = layoutGraph(ALL, [S1, S2], { seasonIds: new Set([2]) })
    expect(onlyS2.nodes.map((n) => n.line.id)).toEqual([5])
    const only = layoutGraph(ALL, [S1, S2], { only: new Set([1, 4]) })
    expect(only.nodes).toHaveLength(2)
    const dropped = [...ALL, line(7, '淘汰的', { status: 'DROPPED' })]
    expect(layoutGraph(dropped, [S1]).nodes.some((n) => n.line.id === 7)).toBe(false)
    expect(layoutGraph(dropped, [S1], { showDropped: true }).nodes.some((n) => n.line.id === 7)).toBe(true)
  })

  it('名称、世代', () => {
    expect(lineLabel({ name: '25-001', code: '25-001' })).toBe('25-001')
    expect(lineLabel({ name: '校麦1号', code: '25-001-3' })).toBe('校麦1号（25-001-3）')
    expect(latestGeneration(AB)).toBe('F1')
    expect(latestGeneration(A)).toBe('')
  })
})
