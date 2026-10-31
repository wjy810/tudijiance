import { describe, expect, it } from 'vitest'

import { MAIN_STAGES, stageIndex, stageView } from '../growthStage'

describe('生育期进度', () => {
  it('一共 8 个主要时期，从播种到成熟', () => {
    expect(MAIN_STAGES.map((s) => s.label)).toEqual(['播种', '出苗', '越冬', '返青', '拔节', '抽穗', '扬花', '成熟'])
  })

  it('主要时期按项目名对上', () => {
    expect(stageIndex('播种期')).toBe(0)
    expect(stageIndex('出苗期')).toBe(1)
    expect(stageIndex('抽穗期')).toBe(5)
    expect(stageIndex('成熟期')).toBe(7)
  })

  it('中间时期归到前一个主要时期', () => {
    expect(stageIndex('分蘖期')).toBe(1)
    expect(stageIndex('起身期')).toBe(3)
    expect(stageIndex('孕穗期')).toBe(4)
    expect(stageIndex('灌浆期')).toBe(6)
  })

  it('不带"期"字、带空格也能认', () => {
    expect(stageIndex('拔节')).toBe(4)
    expect(stageIndex(' 扬花期 ')).toBe(6)
  })

  it('没记录或认不出来返回 -1', () => {
    expect(stageIndex(null)).toBe(-1)
    expect(stageIndex('')).toBe(-1)
    expect(stageIndex('自定义观察期')).toBe(-1)
  })

  it('中间时期的说法会带上它属于哪个主要时期', () => {
    expect(stageView('出苗期')).toEqual({ index: 1, caption: '出苗期' })
    expect(stageView('分蘖期')).toEqual({ index: 1, caption: '分蘖期（出苗之后）' })
    expect(stageView(null)).toEqual({ index: -1, caption: null })
    expect(stageView('自定义观察期')).toEqual({ index: -1, caption: '自定义观察期' })
  })
})
