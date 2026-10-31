<script setup lang="ts">
// 空状态：一幅线描插画 + 一句说明，下面可以放操作按钮
import fieldArt from '@/assets/art/empty-field.webp'
import chartArt from '@/assets/art/empty-chart.webp'
import dataArt from '@/assets/art/empty-data.webp'
import importArt from '@/assets/art/empty-import.webp'
import doneArt from '@/assets/art/empty-done.webp'

const props = withDefaults(defineProps<{
  art?: 'field' | 'chart' | 'data' | 'import' | 'done'
  title: string
  desc?: string
  /** 插画高度（像素） */
  size?: number
}>(), { art: 'data', desc: undefined, size: 120 })

const ART = { field: fieldArt, chart: chartArt, data: dataArt, import: importArt, done: doneArt }
</script>

<template>
  <div class="empty">
    <img :src="ART[props.art]" alt="" :style="{ height: props.size + 'px' }" draggable="false" />
    <div class="title">{{ title }}</div>
    <div v-if="desc" class="desc">{{ desc }}</div>
    <div v-if="$slots.default" class="actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 26px 16px 22px;
}

img {
  width: auto;
  max-width: 80%;
  object-fit: contain;
  opacity: 0.92;
  user-select: none;
}

.title {
  margin-top: 14px;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 15px;
  color: var(--forest);
}

.desc {
  margin-top: 6px;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
}

.actions {
  margin-top: 14px;
  display: flex;
  gap: 8px;
}
</style>
