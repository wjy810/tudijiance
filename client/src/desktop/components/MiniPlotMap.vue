<script setup lang="ts">
// 实验田缩略图：每个小区一个色块，颜色表示状态。首页用
// labels：格子里写小区编号（格子够大时才写）；entered：今天录过数据的小区，右上角加绿点
import { computed } from 'vue'

import type { ScreenPlot } from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'

const props = withDefaults(defineProps<{
  rows: number
  cols: number
  plots: ScreenPlot[]
  labels?: boolean
  entered?: number[]
}>(), { labels: false, entered: () => [] })

const gap = computed(() => (props.cols > 20 ? 1 : props.cols > 10 ? 2 : 3))
const enteredSet = computed(() => new Set(props.entered))
const showCodes = computed(() => props.labels && props.cols <= 12)

function isPlot(p: ScreenPlot): boolean {
  return p.status !== 'GUARD' && p.status !== 'PATH'
}
</script>

<template>
  <div
    class="mini-map"
    :style="{
      gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))`,
      gridTemplateRows: `repeat(${rows}, auto)`,
      gap: gap + 'px',
    }"
  >
    <div
      v-for="p in plots"
      :key="p.plotId"
      class="cell"
      :class="{ alert: p.status === 'ABNORMAL', side: !isPlot(p), today: enteredSet.has(p.plotId) }"
      :style="{ gridRow: p.rowNo, gridColumn: p.colNo, background: PLOT_STATUS[p.status].color }"
      :title="`${p.code ?? ''} ${p.varietyName ?? ''} · ${PLOT_STATUS[p.status].label}${p.abnormalNote ? '：' + p.abnormalNote : ''}${enteredSet.has(p.plotId) ? ' · 今天录过' : ''}`"
    >
      <span v-if="showCodes && isPlot(p) && p.code" class="code num">{{ p.code }}</span>
    </div>
  </div>
</template>

<style scoped>
.mini-map {
  display: grid;
  width: 100%;
}

.cell {
  position: relative;
  aspect-ratio: 1.5;
  border-radius: 3px;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px rgba(80, 60, 20, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.cell.side {
  opacity: 0.75;
}

.cell.alert {
  outline: 1.5px solid var(--clay);
  outline-offset: -1.5px;
}

.cell.today::after {
  content: '';
  position: absolute;
  top: 3px;
  right: 3px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 0 1.5px rgba(255, 255, 255, 0.85);
}

.code {
  font-size: 10px;
  line-height: 1;
  color: rgba(40, 50, 35, 0.55);
  letter-spacing: 0.02em;
  user-select: none;
}
</style>
