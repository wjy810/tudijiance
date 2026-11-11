<script setup lang="ts">
// 区块布局的小预览（SVG）：区块、道路、保护区和区块里的小区块格子，按实际比例
import { computed } from 'vue'

import { blockRect, drawOrder, fieldSize, innerCells, type FieldLayout } from '@/shared/fieldLayout'

const props = withDefaults(defineProps<{ layout: FieldLayout; height?: number }>(), { height: 180 })

const box = computed(() => {
  const s = fieldSize(props.layout)
  return { w: Math.max(s.w, 1e-6), h: Math.max(s.h, 1e-6) }
})

/** 线宽按 viewBox 换算成大约 1 像素 */
const hair = computed(() => Math.max(box.value.w, box.value.h) / 400)

const shapes = computed(() => drawOrder(props.layout).map((b) => ({
  id: b.id,
  kind: b.kind,
  rect: blockRect(props.layout, b),
  cells: b.kind === 'PLANT' ? innerCells(props.layout, b).map((c) => c.rect) : [],
})))

const FILL: Record<string, string> = { PLANT: '#e7dcc2', ROAD: '#d9cfb9', GUARD: '#d6e3c6', BLANK: '#eee6d4' }
</script>

<template>
  <svg
    class="layout-preview"
    :viewBox="`${-hair * 2} ${-hair * 2} ${box.w + hair * 4} ${box.h + hair * 4}`"
    :style="{ height: height + 'px' }"
    preserveAspectRatio="xMidYMid meet"
    role="img"
    aria-label="地块布局预览"
  >
    <rect x="0" y="0" :width="box.w" :height="box.h" fill="#cdbf9f" stroke="#1c4231" :stroke-width="hair * 2" />
    <g v-for="s in shapes" :key="s.id">
      <rect :x="s.rect.x" :y="s.rect.y" :width="s.rect.w" :height="s.rect.h" :fill="FILL[s.kind]" stroke="rgba(28,66,49,0.5)" :stroke-width="hair" />
      <rect
        v-for="(c, i) in s.cells"
        :key="i"
        :x="c.x + hair * 0.8"
        :y="c.y + hair * 0.8"
        :width="Math.max(c.w - hair * 1.6, 0)"
        :height="Math.max(c.h - hair * 1.6, 0)"
        fill="#f6f0df"
        stroke="rgba(90,70,30,0.25)"
        :stroke-width="hair * 0.6"
      />
    </g>
  </svg>
</template>

<style scoped>
.layout-preview {
  display: block;
  width: 100%;
}
</style>
