<script setup lang="ts">
// 手机上按区块选小区：每个区块一张卡片，卡片里是这个区块的小区网格（合并的小区占几格就画几格）。
// 点小区选中或取消，点区块里的行号、列号选这一行、一列，"选整块"选这个区块的所有小区，长按小区看详情。
// 上面的区块标签可以只看某一个区块，格子放大更好点
import { computed, ref, watch } from 'vue'

import { anchors, keyOf, type FieldLayout, type LayoutBlock } from '@/shared/fieldLayout'
import type { PickerCell } from './picker'
import EmptyState from '@/desktop/components/EmptyState.vue'

const props = withDefaults(defineProps<{
  layout: FieldLayout
  cells: PickerCell[]
  selected: Set<number>
  scale?: number
}>(), { scale: 1 })

const emit = defineEmits<{
  toggle: [plotId: number]
  /** 一批小区：都选上了就取消，否则选上 */
  many: [plotIds: number[]]
  hold: [plotId: number]
}>()

const byId = computed(() => new Map(props.cells.map((c) => [c.plotId, c])))

/** 种植区块，从上到下、从左到右 */
const blocks = computed(() => props.layout.blocks
  .filter((b) => b.kind === 'PLANT' && b.inner)
  .sort((a, b) => a.r - b.r || a.c - b.c))

const focus = ref<string | null>(null)
watch(blocks, (list) => {
  if (focus.value && !list.some((b) => b.id === focus.value)) focus.value = null
})
const shown = computed(() => (focus.value ? blocks.value.filter((b) => b.id === focus.value) : blocks.value))

interface Slot {
  key: string
  r: number
  c: number
  rs: number
  cs: number
  cell: PickerCell | null
}

function slotsOf(b: LayoutBlock): Slot[] {
  const inner = b.inner!
  return anchors(inner.rows.length, inner.cols.length, inner.merges).map((s) => {
    const id = inner.plots[keyOf(s.r, s.c)]
    return { key: keyOf(s.r, s.c), ...s, cell: id != null ? byId.value.get(id) ?? null : null }
  })
}

function idsOf(b: LayoutBlock, pick?: (s: Slot) => boolean): number[] {
  return slotsOf(b).filter((s) => s.cell?.selectable && (!pick || pick(s))).map((s) => s.cell!.plotId)
}

function selectedIn(b: LayoutBlock): number {
  return slotsOf(b).filter((s) => s.cell && props.selected.has(s.cell.plotId)).length
}

const cellW = computed(() => Math.round((focus.value ? 92 : 74) * props.scale))
const cellH = computed(() => Math.round((focus.value ? 72 : 58) * props.scale))

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
  if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 10) {
    window.clearTimeout(timer)
    start = null
  }
}

function up(c: PickerCell) {
  window.clearTimeout(timer)
  if (start && start.id === c.plotId && !held && c.selectable) emit('toggle', c.plotId)
  start = null
}

function cancel() {
  window.clearTimeout(timer)
  start = null
}
</script>

<template>
  <div class="block-picker" :style="{ '--w': cellW + 'px', '--h': cellH + 'px', '--fs': (13 * scale).toFixed(1) + 'px' }">
    <nav class="tabs" aria-label="选区块">
      <button type="button" :class="{ on: !focus }" @click="focus = null">全部 {{ blocks.length }}</button>
      <button
        v-for="b in blocks" :key="b.id" type="button" :class="{ on: focus === b.id, has: selectedIn(b) > 0 }"
        @click="focus = focus === b.id ? null : b.id"
      >
        {{ b.name }}<i v-if="selectedIn(b)" class="num">{{ selectedIn(b) }}</i>
      </button>
    </nav>

    <div class="scroll">
      <section v-for="b in shown" :key="b.id" class="block">
        <header class="block-head">
          <span class="block-name">{{ b.name }}</span>
          <span class="block-meta">{{ b.inner!.rows.length }} 行 × {{ b.inner!.cols.length }} 列 · {{ slotsOf(b).length }} 个小区</span>
          <button type="button" class="select-block" :disabled="idsOf(b).length === 0" @click="emit('many', idsOf(b))">
            {{ idsOf(b).length > 0 && idsOf(b).every((id) => selected.has(id)) ? '取消整块' : '选整块' }}
          </button>
        </header>
        <div class="grid-wrap">
          <div
            class="grid"
            :style="{
              gridTemplateColumns: `26px repeat(${b.inner!.cols.length}, var(--w))`,
              gridTemplateRows: `24px repeat(${b.inner!.rows.length}, var(--h))`,
            }"
          >
            <div class="corner" />
            <button
              v-for="(_, c) in b.inner!.cols" :key="'c' + c" type="button" class="col-head"
              :style="{ gridColumn: c + 2, gridRow: 1 }"
              @click="emit('many', idsOf(b, (s) => c >= s.c && c < s.c + s.cs))"
            >
              {{ c + 1 }}
            </button>
            <button
              v-for="(_, r) in b.inner!.rows" :key="'r' + r" type="button" class="row-head"
              :style="{ gridRow: r + 2, gridColumn: 1 }"
              @click="emit('many', idsOf(b, (s) => r >= s.r && r < s.r + s.rs))"
            >
              {{ r + 1 }}
            </button>
            <template v-for="s in slotsOf(b)" :key="s.key">
              <div
                v-if="s.cell"
                class="cell"
                :class="{ on: selected.has(s.cell.plotId), off: !s.cell.selectable, abnormal: s.cell.abnormal }"
                :style="{ gridRow: `${s.r + 2} / span ${s.rs}`, gridColumn: `${s.c + 2} / span ${s.cs}`, background: s.cell.fill }"
                @pointerdown="down($event, s.cell)"
                @pointermove="move"
                @pointerup="up(s.cell)"
                @pointercancel="cancel"
                @pointerleave="cancel"
                @contextmenu.prevent
              >
                <span class="code">{{ s.cell.code }}</span>
                <span class="sub">{{ s.cell.sub }}</span>
                <span v-if="s.cell.third" class="third">{{ s.cell.third }}</span>
                <span v-if="s.cell.ck" class="ck">CK</span>
                <i v-if="s.cell.unsynced" class="dot unsynced" />
                <i v-else-if="s.cell.today" class="dot today" />
                <van-icon v-if="selected.has(s.cell.plotId)" name="success" class="check" />
              </div>
              <div v-else class="cell pending" :style="{ gridRow: `${s.r + 2} / span ${s.rs}`, gridColumn: `${s.c + 2} / span ${s.cs}` }">
                新小区
              </div>
            </template>
          </div>
        </div>
      </section>
      <EmptyState v-if="blocks.length === 0" art="blocks" title="这块地还没有种植区块" desc="请老师在电脑上的“编辑地块”里设好区块和小区" :size="110" />
    </div>
  </div>
</template>

<style scoped>
.block-picker {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.tabs {
  display: flex;
  gap: 8px;
  padding: 2px 12px 8px;
  overflow-x: auto;
  scrollbar-width: none;
  flex-shrink: 0;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tabs button {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 13.5px;
}

.tabs button.on {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
  font-weight: 600;
}

.tabs button i {
  font-style: normal;
  font-size: 11px;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  border-radius: 9px;
  background: var(--wheat-bright);
  color: #3b2a0c;
  text-align: center;
  padding: 0 4px;
}

.scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 0 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.block {
  background: rgba(255, 253, 248, 0.82);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 10px 0 10px 8px;
  box-shadow: 0 2px 8px rgba(60, 45, 15, 0.06);
}

.block-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px 8px 4px;
}

.block-name {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 16px;
  color: var(--forest);
}

.block-meta {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.select-block {
  flex-shrink: 0;
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--green);
  background: var(--green-wash);
  color: var(--green);
  font-size: 12.5px;
  font-weight: 600;
}

.select-block:disabled {
  opacity: 0.4;
}

.grid-wrap {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-x pan-y;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.grid {
  display: grid;
  gap: 4px;
  width: max-content;
  padding-right: 10px;
}

.corner,
.col-head,
.row-head {
  border: none;
  font: inherit;
  font-size: 11.5px;
  font-weight: 700;
  color: #9a8f74;
  background: #ece4d2;
  padding: 0;
}

.col-head {
  border-radius: 7px 7px 0 0;
}

.row-head {
  border-radius: 7px 0 0 7px;
}

.corner {
  background: transparent;
}

.cell {
  position: relative;
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
  min-width: 0;
}

.cell:active {
  transform: scale(0.97);
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

.cell.pending {
  border: 1px dashed rgba(46, 107, 79, 0.5);
  background: transparent;
  color: var(--ink-3);
  font-size: 11px;
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

.none {
  padding: 40px 0;
  text-align: center;
  color: var(--ink-3);
}
</style>
