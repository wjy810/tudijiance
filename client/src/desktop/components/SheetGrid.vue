<script setup lang="ts">
// 导入表格时用的表格视图：像 Excel 一样显示整张表（只渲染看得见的行），
// 拖动鼠标选数据区域；双击格子直接改内容；区域里的表头、各字段的用途、改过的格子、出错的格子用颜色标出来
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { ImportOrientation, ImportRange } from '@/shared/api'
import { columnLetter } from '@/shared/importGuess'
import type { FieldTone } from './sheetGrid'

const props = defineProps<{
  rows: string[][]
  colCount: number
  range: ImportRange | null
  orientation: ImportOrientation
  /** 区域里第几个字段 → 用途 */
  tones: Map<number, FieldTone>
  /** "行,列" → 改后的内容 */
  edits: Map<string, string>
  /** "行,列" → 出错原因 */
  errors: Map<string, string>
  /** 要滚动到并高亮的格子 */
  focus?: { r: number; c: number } | null
}>()

const emit = defineEmits<{
  'update:range': [range: ImportRange]
  edit: [row: number, col: number, value: string]
}>()

const ROW_H = 30
const COL_W = 116
const HEAD_W = 52
const OVERSCAN = 8

const scroller = ref<HTMLDivElement | null>(null)
const scrollTop = ref(0)
const viewHeight = ref(600)

const totalRows = computed(() => Math.max(props.rows.length, 1))
const cols = computed(() => Array.from({ length: Math.max(props.colCount, 1) }, (_, i) => i))
const first = computed(() => Math.max(0, Math.floor(scrollTop.value / ROW_H) - OVERSCAN))
const last = computed(() => Math.min(totalRows.value - 1, Math.ceil((scrollTop.value + viewHeight.value) / ROW_H) + OVERSCAN))
const visibleRows = computed(() => {
  const list: number[] = []
  for (let r = first.value; r <= last.value; r++) list.push(r)
  return list
})

function onScroll() {
  const el = scroller.value
  if (el) {
    scrollTop.value = el.scrollTop
    viewHeight.value = el.clientHeight
  }
}

const key = (r: number, c: number) => `${r},${c}`
const value = (r: number, c: number) => props.edits.get(key(r, c)) ?? props.rows[r]?.[c] ?? ''

function inRange(r: number, c: number, range = shownRange.value): boolean {
  return !!range && r >= range.top && r <= range.bottom && c >= range.left && c <= range.right
}

/** 格子在区域里属于第几个字段；是表头时 header 为 true */
function fieldOf(r: number, c: number): { field: number; header: boolean } | null {
  const range = shownRange.value
  if (!range || !inRange(r, c, range)) return null
  return props.orientation === 'COLUMNS'
    ? { field: c - range.left, header: r === range.top }
    : { field: r - range.top, header: c === range.left }
}

function cellClass(r: number, c: number) {
  const f = fieldOf(r, c)
  const k = key(r, c)
  return {
    out: !!shownRange.value && !f,
    header: f?.header,
    [`tone-${props.tones.get(f?.field ?? -1) ?? 'none'}`]: !!f && !f.header,
    edited: props.edits.has(k),
    error: props.errors.has(k),
    focus: props.focus?.r === r && props.focus?.c === c,
  }
}

// ---------- 拖动选区域 ----------
const dragging = ref<{ r: number; c: number } | null>(null)
const dragRange = ref<ImportRange | null>(null)
const shownRange = computed(() => dragRange.value ?? props.range)

function onCellDown(e: MouseEvent, r: number, c: number) {
  if (e.button !== 0 || editing.value) return
  dragging.value = { r, c }
  dragRange.value = { top: r, left: c, bottom: r, right: c }
  window.addEventListener('mouseup', onUp, { once: true })
}

function onCellEnter(r: number, c: number) {
  const a = dragging.value
  if (!a) return
  dragRange.value = { top: Math.min(a.r, r), left: Math.min(a.c, c), bottom: Math.max(a.r, r), right: Math.max(a.c, c) }
}

function onUp() {
  const range = dragRange.value
  dragging.value = null
  dragRange.value = null
  // 只点了一下（没拖成区域）不改区域，免得误操作
  if (range && (range.bottom > range.top || range.right > range.left)) {
    emit('update:range', range)
  }
}

onBeforeUnmount(() => window.removeEventListener('mouseup', onUp))

// ---------- 双击改格子 ----------
const editing = ref<{ r: number; c: number; text: string } | null>(null)
const editor = ref<HTMLInputElement | null>(null)
// 输入框在 v-for 里，用函数 ref 拿到唯一的那一个
function setEditor(el: unknown) {
  editor.value = (el as HTMLInputElement | null) ?? null
}

async function startEdit(r: number, c: number) {
  editing.value = { r, c, text: value(r, c) }
  await nextTick()
  editor.value?.focus()
  editor.value?.select()
}

function commitEdit() {
  const e = editing.value
  if (!e) return
  editing.value = null
  if (e.text !== value(e.r, e.c)) {
    emit('edit', e.r, e.c, e.text)
  }
}

// 外面要求定位到某个格子：滚动过去，并直接进入编辑。
// 从"检查"回到表格时组件是新建的，所以挂载时也要定位一次
watch(() => props.focus, (f) => goTo(f))
onMounted(() => goTo(props.focus))

async function goTo(f: { r: number; c: number } | null | undefined) {
  if (!f || !scroller.value) return
  const el = scroller.value
  const top = f.r * ROW_H
  if (top < el.scrollTop || top > el.scrollTop + el.clientHeight - ROW_H * 3) {
    el.scrollTop = Math.max(0, top - el.clientHeight / 2)
  }
  const left = HEAD_W + f.c * COL_W
  if (left < el.scrollLeft || left > el.scrollLeft + el.clientWidth - COL_W) {
    el.scrollLeft = Math.max(0, left - el.clientWidth / 2)
  }
  onScroll()
  await startEdit(f.r, f.c)
}

const width = computed(() => HEAD_W + cols.value.length * COL_W)
</script>

<template>
  <div ref="scroller" class="sheet" @scroll="onScroll">
    <div class="canvas" :style="{ width: width + 'px', height: (totalRows * ROW_H + ROW_H) + 'px' }">
      <!-- 列字母 -->
      <div class="col-heads" :style="{ width: width + 'px' }">
        <div class="corner" />
        <div
          v-for="c in cols"
          :key="c"
          class="col-head"
          :class="{ on: !!shownRange && c >= shownRange.left && c <= shownRange.right }"
        >{{ columnLetter(c) }}</div>
      </div>

      <div
        v-for="r in visibleRows"
        :key="r"
        class="row"
        :style="{ transform: `translateY(${(r + 1) * ROW_H}px)` }"
      >
        <div class="row-head" :class="{ on: !!shownRange && r >= shownRange.top && r <= shownRange.bottom }">{{ r + 1 }}</div>
        <div
          v-for="c in cols"
          :key="c"
          class="cell"
          :class="cellClass(r, c)"
          :title="errors.get(key(r, c)) ?? (edits.has(key(r, c)) ? `原来是：${rows[r]?.[c] ?? ''}` : undefined)"
          @mousedown="onCellDown($event, r, c)"
          @mouseenter="onCellEnter(r, c)"
          @dblclick="startEdit(r, c)"
        >
          <input
            v-if="editing && editing.r === r && editing.c === c"
            :ref="setEditor"
            v-model="editing.text"
            class="editor"
            @keydown.enter.prevent="commitEdit"
            @keydown.esc.prevent="editing = null"
            @blur="commitEdit"
            @mousedown.stop
          />
          <span v-else>{{ value(r, c) }}</span>
        </div>
      </div>

      <!-- 区域的外框 -->
      <div
        v-if="shownRange"
        class="range-box"
        :class="{ dragging: !!dragRange }"
        :style="{
          left: HEAD_W + shownRange.left * COL_W + 'px',
          top: (shownRange.top + 1) * ROW_H + 'px',
          width: (shownRange.right - shownRange.left + 1) * COL_W + 'px',
          height: (shownRange.bottom - shownRange.top + 1) * ROW_H + 'px',
        }"
      />
    </div>
  </div>
</template>

<style scoped>
.sheet {
  position: relative;
  overflow: auto;
  height: 100%;
  background: #fbf8f1;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.canvas {
  position: relative;
}

.col-heads {
  position: sticky;
  top: 0;
  z-index: 4;
  display: flex;
  height: 30px;
}

.corner,
.row-head {
  position: sticky;
  left: 0;
  width: 52px;
  flex-shrink: 0;
  z-index: 3;
}

.corner {
  background: var(--paper-deep);
  border-right: 1px solid var(--line-strong);
  border-bottom: 1px solid var(--line-strong);
  z-index: 5;
}

.col-head,
.row-head {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8d8670;
  font-size: 11.5px;
  font-weight: 600;
  background: var(--paper-deep);
}

.col-head {
  width: 116px;
  flex-shrink: 0;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line-strong);
}

.col-head.on,
.row-head.on {
  background: #e7d9b4;
  color: #6b5216;
}

.row {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  height: 30px;
  will-change: transform;
}

.row-head {
  height: 30px;
  border-right: 1px solid var(--line-strong);
  border-bottom: 1px solid var(--line);
}

.cell {
  position: relative;
  width: 116px;
  flex-shrink: 0;
  height: 30px;
  box-sizing: border-box;
  padding: 0 8px;
  line-height: 29px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  border-right: 1px solid #efe8d8;
  border-bottom: 1px solid #efe8d8;
  background: #fffdf8;
  cursor: cell;
}

.cell.out {
  background: #f6f2e8;
  color: #b5ad98;
}

.cell.header {
  background: #f6e7c3;
  color: #5a4210;
  font-weight: 700;
}

.cell.tone-key {
  background: #eaf2f5;
}

.cell.tone-trait {
  background: #edf4ea;
}

.cell.tone-ignore {
  color: #a8a08a;
  background: repeating-linear-gradient(135deg, #fbf8f1 0 6px, #f5f0e4 6px 7px);
}

/* 改过的格子：右上角一个金色小三角 */
.cell.edited::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  border-style: solid;
  border-width: 0 9px 9px 0;
  border-color: transparent #d9a748 transparent transparent;
}

.cell.error {
  box-shadow: inset 0 0 0 2px #c4593b;
  background: #fbe9e2;
  color: #8d3a22;
}

.cell.focus {
  box-shadow: inset 0 0 0 2px var(--green);
}

.editor {
  position: absolute;
  inset: 0;
  width: 100%;
  border: none;
  outline: 2px solid var(--green);
  outline-offset: -2px;
  padding: 0 7px;
  font: inherit;
  background: #fff;
  user-select: text;
}

.range-box {
  position: absolute;
  pointer-events: none;
  border: 2px solid var(--green);
  border-radius: 2px;
  box-shadow: 0 0 0 3px rgba(46, 107, 79, 0.15);
  z-index: 2;
}

.range-box.dragging {
  border-style: dashed;
  background: rgba(46, 107, 79, 0.06);
}
</style>
