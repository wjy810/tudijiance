<script setup lang="ts">
// ECharts 图表容器：跟着容器大小自动缩放，option 变了就更新
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { init, type EChartsCoreOption, type ECharts } from 'echarts/core'

import '@/shared/charts'

const props = defineProps<{ option: EChartsCoreOption; height?: string }>()

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<ECharts | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) {
    return
  }
  chart.value = init(el.value)
  chart.value.setOption(props.option)
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

watch(() => props.option, (option) => {
  // 换成新的数据时整体替换，避免旧系列残留
  chart.value?.setOption(option, { notMerge: true })
})

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
})

/** 导出图片用：图表画成 png 的 data URL */
function toDataURL(background = '#fffdf8'): string | null {
  return chart.value?.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: background }) ?? null
}

defineExpose({ toDataURL })
</script>

<template>
  <div ref="el" class="echart" :style="{ height: height ?? '320px' }" />
</template>

<style scoped>
.echart {
  width: 100%;
}
</style>
