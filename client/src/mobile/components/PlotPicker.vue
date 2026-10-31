<script setup lang="ts">
// 手机上的平面图：点小区选中或取消，点行号、列号选整行、整列，长按小区看详情。
// 格子够大，手指好点；横竖都能滑动，行号、列号固定在边上
import { computed } from 'vue'

import type { PickerCell } from './picker'


const props = withDefaults(defineProps<{
  rows: number
  cols: number
  cells: PickerCell[]
  selected: Set<number>
  /** 缩放：1 是正常大小 */
  scale?: number
}>(), { scale: 1 })

const emit = defineEmits<{
  toggle: [plotId: number]
  line: [kind: 'row' | 'col', index: number]
  hold: [plotId: number]
}>()

const cellW = computed(() => Math.round(78 * props.scale))
const cellH = computed(() => Math.round(64 * props.scale))
const byPos = computed(() => new Map(props.cells.map((c) => [`${c.rowNo}-${c.colNo}`, c])))
const rowList = computed(() => Array.from({ length: props.rows }, (_, i) => i + 1))
const colList = computed(() => Array.from({ length: props.cols }, (_, i) => i + 1))

// ---------- 点按和长按 ----------
let timer: number | undefined
let start: { x: number; y: number; id: number } | null = null
let held = false

function down(e: PointerEvent, c: PickerCell) {
  start = { x: e.clientX, y: e.clientY, id: c.plotId }
  held = false
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    held = true
    navigator.vibrate?.(20)
    emit('hold', c.plotId)
  }, 520)
}

function move(e: PointerEvent) {
  // 手指在滑动画面，不算点按
  if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 10) {
    window.clearTimeout(timer)
    start = null
  }
}

function up(c: PickerCell) {
  window.clearTimeout(timer)
  if (start && start.id === c.plotId && !held && c.selectable) {
    emit('toggle', c.plotId)
  }
  start = null
}

function cancel() {
  window.clearTimeout(timer)
  start = null
}
</script>

<template>
  <div class="picker" :style="{ '--w': cellW + 'px', '--h': cellH + 'px', '--fs': (13 * scale).toFixed(1) + 'px' }">
    <div class="grid" :style="{ gridTemplateColumns: `36px repeat(${cols}, var(--w))` }">
      <div class="corner" />
      <button v-for="c in colList" :key="'c' + c" type="button" class="col-head" @click="emit('line', 'col', c)">{{ c }}</button>
      <template v-for="r in rowList" :key="'r' + r">
        <button type="button" class="row-head" @click="emit('line', 'row', r)">{{ r }}</button>
        <template v-for="c in colList" :key="r + '-' + c">
          <div
            v-if="byPos.get(`${r}-${c}`)"
            class="cell"
            :class="{
              on: selected.has(byPos.get(`${r}-${c}`)!.plotId),
              off: !byPos.get(`${r}-${c}`)!.selectable,
              abnormal: byPos.get(`${r}-${c}`)!.abnormal,
            }"
            :style="{ background: byPos.get(`${r}-${c}`)!.fill }"
            @pointerdown="down($event, byPos.get(`${r}-${c}`)!)"
            @pointermove="move"
            @pointerup="up(byPos.get(`${r}-${c}`)!)"
            @pointercancel="cancel"
            @pointerleave="cancel"
            @contextmenu.prevent
          >
            <span class="code">{{ byPos.get(`${r}-${c}`)!.code }}</span>
            <span class="sub">{{ byPos.get(`${r}-${c}`)!.sub }}</span>
            <span v-if="byPos.get(`${r}-${c}`)!.third" class="third">{{ byPos.get(`${r}-${c}`)!.third }}</span>
            <span v-if="byPos.get(`${r}-${c}`)!.ck" class="ck">CK</span>
            <i v-if="byPos.get(`${r}-${c}`)!.unsynced" class="dot unsynced" />
            <i v-else-if="byPos.get(`${r}-${c}`)!.today" class="dot today" />
            <van-icon v-if="selected.has(byPos.get(`${r}-${c}`)!.plotId)" name="success" class="check" />
          </div>
          <div v-else class="cell empty" />
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.picker {
  overflow: auto;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-x pan-y;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.grid {
  display: grid;
  gap: 4px;
  padding: 0 10px 10px 0;
  width: max-content;
}

.corner,
.col-head,
.row-head {
  position: sticky;
  z-index: 2;
  border: none;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  color: #9a8f74;
  background: #ece4d2;
}

.corner {
  top: 0;
  left: 0;
  z-index: 3;
}

.col-head {
  top: 0;
  height: 28px;
  border-radius: 0 0 8px 8px;
}

.row-head {
  left: 0;
  width: 36px;
  border-radius: 0 8px 8px 0;
}

.cell {
  position: relative;
  width: var(--w);
  height: var(--h);
  box-sizing: border-box;
  padding: 4px 3px;
  border-radius: 9px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  border: 1px solid rgba(90, 70, 30, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6);
  overflow: hidden;
  font-size: var(--fs);
  transition: transform 0.08s;
}

.cell:active {
  transform: scale(0.97);
}

.cell.empty {
  background: transparent;
  border: none;
  box-shadow: none;
}

.cell.off {
  opacity: 0.75;
}

.cell.on {
  border: 2.5px solid var(--forest);
  box-shadow: 0 0 0 3px rgba(226, 182, 90, 0.55);
}

.cell.abnormal {
  outline: 2px solid var(--clay);
  outline-offset: -3px;
}

.code {
  font-weight: 700;
  color: #5d5643;
  line-height: 1.2;
}

.sub,
.third {
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink);
  line-height: 1.25;
}

.third {
  font-size: 0.9em;
  color: var(--ink-2);
}

.ck {
  position: absolute;
  top: 3px;
  right: 3px;
  padding: 0 3px;
  border-radius: 4px;
  background: #d9a748;
  color: #3b2a0c;
  font-size: 9px;
  font-weight: 700;
  line-height: 13px;
}

.dot {
  position: absolute;
  left: 5px;
  bottom: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  box-shadow: 0 0 0 1.5px #fff;
}

.dot.today {
  background: var(--green);
}

.dot.unsynced {
  background: #e08a2c;
}

.check {
  position: absolute;
  right: 3px;
  bottom: 3px;
  font-size: 13px;
  color: #fff;
  background: var(--forest);
  border-radius: 50%;
  padding: 2px;
}
</style>
