<script setup lang="ts">
// 指北针：雕版风格的罗盘跟着北转，表圈上的"北东南西"保持正着。
// 自由视角下可以按住表盘拖动来转画面；点一下让北朝上
import { computed, onBeforeUnmount, ref } from 'vue'

import { northOnScreen, normDeg } from '@/shared/compass'
import rose from '@/assets/art/compass-rose.webp'

const props = withDefaults(defineProps<{
  northDeg: number
  rotation: number
  /** 能不能拖动表盘转画面（固定视角时不能） */
  rotatable?: boolean
  size?: number
}>(), { rotatable: true, size: 84 })

const emit = defineEmits<{
  /** 拖动表盘：画面要转到的角度 */
  rotate: [deg: number]
  northUp: []
}>()

const north = computed(() => northOnScreen(props.northDeg, props.rotation))

/** 东南西北四个字放在表圈上（罗盘外圈刻度的位置），字本身不转 */
const letters = computed(() => ['北', '东', '南', '西'].map((t, i) => {
  const a = ((north.value + i * 90) * Math.PI) / 180
  const d = props.size / 2 - 9
  return { t, x: props.size / 2 + Math.sin(a) * d, y: props.size / 2 - Math.cos(a) * d, main: i === 0 }
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
    <img :src="rose" alt="" class="rose" :style="{ transform: `rotate(${north}deg)` }" draggable="false">
    <span
      v-for="l in letters" :key="l.t" class="letter" :class="{ main: l.main }"
      :style="{ left: l.x + 'px', top: l.y + 'px' }"
    >{{ l.t }}</span>
  </div>
</template>

<style scoped>
.compass {
  position: relative;
  border-radius: 50%;
  cursor: pointer;
  user-select: none;
  filter: drop-shadow(0 5px 12px rgba(40, 30, 10, 0.22));
}

.compass.rotatable {
  cursor: grab;
}

.compass:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}

.rose {
  width: 100%;
  height: 100%;
  display: block;
  pointer-events: none;
}

.letter {
  position: absolute;
  width: 17px;
  height: 17px;
  margin: -8.5px 0 0 -8.5px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--surface);
  border: 1px solid var(--forest);
  color: var(--forest);
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  pointer-events: none;
}

.letter.main {
  width: 19px;
  height: 19px;
  margin: -9.5px 0 0 -9.5px;
  background: var(--clay);
  border-color: var(--clay);
  color: #fff6ee;
  font-size: 11px;
}
</style>
