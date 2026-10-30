<script setup lang="ts">
// 实验田缩略图：每个小区一个色块，颜色表示状态。首页卡片上用
import { computed } from 'vue'

import type { ScreenPlot } from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'

const props = defineProps<{ rows: number; cols: number; plots: ScreenPlot[] }>()

const gap = computed(() => (props.cols > 20 ? 1 : 3))
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
      :class="{ alert: p.status === 'ABNORMAL' }"
      :style="{ gridRow: p.rowNo, gridColumn: p.colNo, background: PLOT_STATUS[p.status].color }"
      :title="`${p.code ?? ''} ${p.varietyName ?? ''} · ${PLOT_STATUS[p.status].label}${p.abnormalNote ? '：' + p.abnormalNote : ''}`"
    />
  </div>
</template>

<style scoped>
.mini-map {
  display: grid;
  width: 100%;
}

.cell {
  aspect-ratio: 1.5;
  border-radius: 2px;
  min-width: 0;
}

.cell.alert {
  outline: 1.5px solid #c94040;
  outline-offset: -1.5px;
}
</style>
