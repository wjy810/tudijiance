<script setup lang="ts">
// 指北针：指针指着屏幕上的北，东南西北四个字跟着转但保持正着。
// 自由视角下可以按住表盘拖动来转画面；点一下让北朝上
import { computed, onBeforeUnmount, ref } from 'vue'

import { northOnScreen, normDeg } from '@/shared/compass'

const props = withDefaults(defineProps<{
  northDeg: number
  rotation: number
  /** 能不能拖动表盘转画面（固定视角时不能） */
  rotatable?: boolean
  size?: number
}>(), { rotatable: true, size: 76 })

const emit = defineEmits<{
  /** 拖动表盘：画面要转到的角度 */
  rotate: [deg: number]
  northUp: []
}>()

const north = computed(() => northOnScreen(props.northDeg, props.rotation))
const r = computed(() => props.size / 2)

const letters = computed(() => ['北', '东', '南', '西'].map((t, i) => {
  const a = ((north.value + i * 90) * Math.PI) / 180
  const d = r.value - 13
  return { t, x: r.value + Math.sin(a) * d, y: r.value - Math.cos(a) * d, main: i === 0 }
}))

const ticks = computed(() => Array.from({ length: 24 }, (_, i) => {
  const a = ((north.value + i * 15) * Math.PI) / 180
  const r1 = r.value - 3
  const r2 = r.value - (i % 6 === 0 ? 8 : 5)
  return {
    x1: r.value + Math.sin(a) * r1, y1: r.value - Math.cos(a) * r1,
    x2: r.value + Math.sin(a) * r2, y2: r.value - Math.cos(a) * r2,
  }
}))

// ---------- 拖动转画面 ----------

const el = ref<HTMLElement | null>(null)
let drag: { start: number; rotation: number; moved: boolean } | null = null

function angleAt(e: MouseEvent): number {
  const b = el.value!.getBoundingClientRect()
  const x = e.clientX - (b.left + b.width / 2)
  const y = e.clientY - (b.top + b.height / 2)
  return (Math.atan2(x, -y) * 180) / Math.PI
}

function onDown(e: MouseEvent) {
  if (e.button !== 0) return
  drag = { start: angleAt(e), rotation: props.rotation, moved: false }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function onMove(e: MouseEvent) {
  if (!drag || !props.rotatable) return
  const delta = angleAt(e) - drag.start
  if (!drag.moved && Math.abs(delta) < 3) return
  drag.moved = true
  let deg = drag.rotation + delta
  // 按住 Shift 时每 15° 一档
  if (e.shiftKey) deg = Math.round(deg / 15) * 15
  emit('rotate', normDeg(deg))
}

function onUp() {
  if (drag && !drag.moved) emit('northUp')
  drag = null
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseup', onUp)
}

onBeforeUnmount(onUp)
</script>

<template>
  <div
    ref="el"
    class="compass"
    :class="{ rotatable }"
    :style="{ width: size + 'px', height: size + 'px' }"
    role="button"
    tabindex="0"
    :aria-label="`指北针：北在屏幕上方顺时针 ${Math.round(north)} 度。点一下让北朝上`"
    :title="rotatable ? '点一下让北朝上；按住拖动转画面（Shift 每 15°）' : '点一下让北朝上'"
    @mousedown.prevent="onDown"
    @keydown.enter="emit('northUp')"
  >
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" aria-hidden="true">
      <circle :cx="r" :cy="r" :r="r - 1" class="face" />
      <circle :cx="r" :cy="r" :r="r - 18" class="inner" />
      <line v-for="(t, i) in ticks" :key="i" v-bind="t" class="tick" :class="{ major: i % 6 === 0 }" />
      <g :transform="`rotate(${north} ${r} ${r})`">
        <polygon :points="`${r},${r - (r - 20)} ${r - 5},${r} ${r + 5},${r}`" class="needle-n" />
        <polygon :points="`${r},${r + (r - 20)} ${r - 5},${r} ${r + 5},${r}`" class="needle-s" />
      </g>
      <circle :cx="r" :cy="r" r="3" class="hub" />
      <text
        v-for="l in letters" :key="l.t" :x="l.x" :y="l.y" class="letter" :class="{ main: l.main }"
        text-anchor="middle" dominant-baseline="central"
      >{{ l.t }}</text>
    </svg>
  </div>
</template>

<style scoped>
.compass {
  border-radius: 50%;
  cursor: pointer;
  filter: drop-shadow(0 4px 10px rgba(40, 30, 10, 0.18));
  user-select: none;
}

.compass.rotatable {
  cursor: grab;
}

.compass:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}

.face {
  fill: rgba(255, 253, 248, 0.94);
  stroke: var(--line-strong);
  stroke-width: 1;
}

.inner {
  fill: none;
  stroke: var(--line);
  stroke-dasharray: 2 3;
}

.tick {
  stroke: var(--ink-4);
  stroke-width: 1;
}

.tick.major {
  stroke: var(--ink-3);
  stroke-width: 1.5;
}

.needle-n {
  fill: var(--clay);
}

.needle-s {
  fill: var(--forest);
  opacity: 0.75;
}

.hub {
  fill: var(--surface);
  stroke: var(--forest);
  stroke-width: 1.5;
}

.letter {
  font-size: 11px;
  fill: var(--ink-2);
  font-weight: 600;
}

.letter.main {
  fill: var(--clay);
  font-size: 12.5px;
  font-weight: 700;
}
</style>
