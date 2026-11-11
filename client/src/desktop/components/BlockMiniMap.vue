<script setup lang="ts">
// 按区块布局画的实验田缩略图（SVG，按实际比例）：区块、道路、保护区，小区块按状态着色。
// 首页（浅色）和数据大屏（深色）用；没保存过区块布局的地还用 MiniPlotMap 按行列画
import { computed } from 'vue'

import type { PlotStatusCode, ScreenPlot } from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'
import { blockRect, drawOrder, fieldSize, innerCells, type FieldLayout, type Rect } from '@/shared/fieldLayout'

const props = withDefaults(defineProps<{
  layout: FieldLayout
  plots: ScreenPlot[]
  /** 状态 → 颜色，不传用平面图的颜色 */
  colors?: Partial<Record<PlotStatusCode, string>>
  /** 今天录过的小区块 */
  entered?: number[]
  /** 格子里写什么：不写、编号、编号和品种（小区块多了自动不写） */
  labels?: 'none' | 'code' | 'full'
  theme?: 'light' | 'dark'
}>(), { colors: () => ({}), entered: () => [], labels: 'none', theme: 'light' })

const size = computed(() => {
  const s = fieldSize(props.layout)
  return { w: Math.max(s.w, 1e-6), h: Math.max(s.h, 1e-6) }
})
/** 大约 1 像素在布局里多长（按 300 像素宽估） */
const hair = computed(() => Math.max(size.value.w, size.value.h) / 300)

const byId = computed(() => new Map(props.plots.map((p) => [p.plotId, p])))
const enteredSet = computed(() => new Set(props.entered))

const DARK = props.theme === 'dark'
const KIND_FILL = DARK
  ? { PLANT: '#13241b', ROAD: '#2b2a22', GUARD: '#1e3326', BLANK: '#1a201c' }
  : { PLANT: '#e7dcc2', ROAD: '#d9cfb9', GUARD: '#d6e3c6', BLANK: '#eee6d4' }

function color(status: PlotStatusCode): string {
  return props.colors[status] ?? PLOT_STATUS[status].color
}

interface CellShape {
  key: string
  r: Rect
  plot: ScreenPlot | null
}

const shapes = computed(() => drawOrder(props.layout).map((b) => {
  const rect = blockRect(props.layout, b)
  const cells: CellShape[] = b.kind === 'PLANT'
    ? innerCells(props.layout, b).map((c) => ({ key: c.key, r: c.rect, plot: c.plotId != null ? byId.value.get(c.plotId) ?? null : null }))
    : []
  return { id: b.id, kind: b.kind, name: b.name, rect, cells }
}))

const cellCount = computed(() => shapes.value.reduce((n, s) => n + s.cells.length, 0))
const showText = computed(() => props.labels !== 'none' && cellCount.value <= 160)

/** 字写在格子中心；窄长的格子竖着写 */
function textOf(c: CellShape) {
  const tall = c.r.h > c.r.w * 1.6
  const along = tall ? c.r.h : c.r.w
  const across = tall ? c.r.w : c.r.h
  // 字不超过整块地宽的 1/40，大格子里也不会大得吓人
  const fs = Math.min(along / 4.2, across * 0.36, hair.value * 7.5)
  const two = props.labels === 'full' && across > fs * 2.6 && !!c.plot?.varietyName
  return {
    x: c.r.x + c.r.w / 2, y: c.r.y + c.r.h / 2, fs, two,
    transform: tall ? `rotate(-90 ${c.r.x + c.r.w / 2} ${c.r.y + c.r.h / 2})` : undefined,
  }
}

function tipOf(c: CellShape): string {
  const p = c.plot
  if (!p) return '新小区块'
  return `${p.code ?? ''} ${p.varietyName ?? ''} · ${PLOT_STATUS[p.status].label}${p.abnormalNote ? '：' + p.abnormalNote : ''}`
    + (enteredSet.value.has(p.plotId) ? ' · 今天录过' : '')
}
</script>

<template>
  <svg
    class="block-mini-map"
    :class="theme"
    :viewBox="`${-hair * 3} ${-hair * 3} ${size.w + hair * 6} ${size.h + hair * 6}`"
    preserveAspectRatio="xMidYMid meet"
    role="img"
    :aria-label="`实验田平面图，${cellCount} 个小区块`"
  >
    <rect
      :x="-hair" :y="-hair" :width="size.w + hair * 2" :height="size.h + hair * 2" :rx="hair * 2"
      :fill="DARK ? '#0b1611' : '#cdbf9f'" :stroke="DARK ? 'rgba(226,182,90,0.35)' : '#1c4231'" :stroke-width="hair * 1.2"
    />
    <g v-for="s in shapes" :key="s.id">
      <rect :x="s.rect.x" :y="s.rect.y" :width="s.rect.w" :height="s.rect.h" :fill="KIND_FILL[s.kind]" />
      <line
        v-if="s.kind === 'ROAD'"
        v-bind="s.rect.w >= s.rect.h
          ? { x1: s.rect.x + hair * 3, y1: s.rect.y + s.rect.h / 2, x2: s.rect.x + s.rect.w - hair * 3, y2: s.rect.y + s.rect.h / 2 }
          : { x1: s.rect.x + s.rect.w / 2, y1: s.rect.y + hair * 3, x2: s.rect.x + s.rect.w / 2, y2: s.rect.y + s.rect.h - hair * 3 }"
        :stroke="DARK ? 'rgba(226,182,90,0.25)' : '#f6f0e2'" :stroke-width="hair" :stroke-dasharray="`${hair * 4} ${hair * 3}`"
      />
      <g v-for="c in s.cells" :key="c.key">
        <rect
          :x="c.r.x + hair * 0.7" :y="c.r.y + hair * 0.7"
          :width="Math.max(c.r.w - hair * 1.4, 0)" :height="Math.max(c.r.h - hair * 1.4, 0)" :rx="hair * 1.2"
          :fill="c.plot ? color(c.plot.status) : 'transparent'"
          :stroke="c.plot?.status === 'ABNORMAL' ? '#c0562f' : c.plot ? (DARK ? 'rgba(0,0,0,0.35)' : 'rgba(90,70,30,0.22)') : 'rgba(46,107,79,0.5)'"
          :stroke-width="c.plot?.status === 'ABNORMAL' ? hair * 1.4 : hair * 0.5"
          :stroke-dasharray="c.plot ? undefined : `${hair * 2} ${hair * 1.5}`"
        >
          <title>{{ tipOf(c) }}</title>
        </rect>
        <template v-if="showText && c.plot && c.plot.status !== 'GUARD' && c.plot.status !== 'PATH'">
          <text
            :x="textOf(c).x" :y="textOf(c).y" :transform="textOf(c).transform" :font-size="textOf(c).fs"
            text-anchor="middle" :dy="textOf(c).two ? -textOf(c).fs * 0.15 : textOf(c).fs * 0.35"
            class="code"
          >{{ c.plot.code }}</text>
          <text
            v-if="textOf(c).two"
            :x="textOf(c).x" :y="textOf(c).y" :transform="textOf(c).transform" :font-size="textOf(c).fs * 0.82"
            text-anchor="middle" :dy="textOf(c).fs * 1.05" class="variety"
          >{{ c.plot.varietyName }}</text>
        </template>
        <circle
          v-if="c.plot && enteredSet.has(c.plot.plotId)"
          :cx="c.r.x + c.r.w - Math.min(c.r.w, c.r.h) * 0.16" :cy="c.r.y + Math.min(c.r.w, c.r.h) * 0.16"
          :r="Math.min(c.r.w, c.r.h) * 0.07" fill="#2e6b4f" stroke="#fff" :stroke-width="hair * 0.5"
        />
      </g>
    </g>
  </svg>
</template>

<style scoped>
.block-mini-map {
  display: block;
  width: 100%;
  height: 100%;
}

.code {
  font-weight: 700;
  fill: #4f4a3a;
  font-family: var(--font-sans);
}

.variety {
  fill: var(--ink);
  font-family: var(--font-sans);
}

.dark .code {
  fill: #f2e6c8;
}

.dark .variety {
  fill: rgba(242, 230, 200, 0.8);
}
</style>
