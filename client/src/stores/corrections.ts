// 更正申请的数字：老师看待审核的条数（侧边栏角标、首页卡片），学生看自己待审核和最近被处理的条数
import { ref } from 'vue'
import { defineStore } from 'pinia'

import { correctionApi, type CorrectionSummary } from '@/shared/api'

export const useCorrectionStore = defineStore('corrections', () => {
  const summary = ref<CorrectionSummary | null>(null)

  async function refresh(): Promise<void> {
    try {
      summary.value = await correctionApi.summary()
    } catch {
      // 模块停用或没网时不显示数字
      summary.value = null
    }
  }

  function reset() {
    summary.value = null
  }

  return { summary, refresh, reset }
})
