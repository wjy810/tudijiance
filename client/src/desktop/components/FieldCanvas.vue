<script setup lang="ts">
// 按实际尺寸画实验田：区块、道路、保护区、空地、区块里的小区。用 Konva 画，可以缩放、平移、旋转，导出 jpg。
//
//   focus 为空时看整块地；为区块 id 时只看这个区块（"进入区块"）
//   pick  点选的是什么：plot 小区（录入、布置试验）、block 区块（编辑地块）、cell 区块里的格子（编辑区块里的小区）
//   locked 固定视角：不能拖动、缩放、旋转，总是正好放下整块地
//
// 选择：单击选一个，Ctrl/Shift 单击加选，拖动框选，点标尺选整行整列；双击小区打开详情，双击区块进入区块。
// 平移：按住滚轮拖动，或按住空格拖动，或打开 panning；滚轮缩放，Shift + 滚轮旋转。
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type Konva from 'konva'
import type { KonvaEventObject } from 'konva/lib/Node'

import { saveFile } from '@/shared/api/http'
import guardTileUrl from '@/assets/art/guard-tile.webp'
import roadTileUrl from '@/assets/art/road-tile.webp'
import {
  anchors, blockRect, blockSlot, blockSpan, fieldSize, fmt, innerCells, innerSizes, keyOf, round, sum, trackRect,
  type FieldLayout, type LayoutBlock, type Rect,
} from '@/shared/fieldLayout'

export interface CanvasCell {
  lines: string[]
  fill: string
  badge?: string
  marker?: boolean
  tooltip?: string
  /** 保护区、走道的小区画得淡一些 */
  muted?: boolean
}

export interface CanvasDevice {
  id: number
  /** 在布局里的位置（单位同布局） */
  x: number
  y: number
  label: string
  color: string
}

export interface CanvasLink {
  id: number
  label: string
  segments: { from: number; to: number }[]
  controlPlotIds: number[]
}

const props = withDefaults(defineProps<{
  layout: FieldLayout
  cells: Map<number, CanvasCell>
  focus?: string | null
  pick?: 'plot' | 'block' | 'cell'
  selectedPlots?: number[]
  selectedBlocks?: string[]
  selectedCells?: string[]
  links?: CanvasLink[]
  devices?: CanvasDevice[]
  rotation?: number
  locked?: boolean
  panning?: boolean
  /** 显示行高列宽标尺（编辑地块时） */
  rulers?: boolean
  /** 可以拖动特殊区块（编辑地块时） */
  draggable?: boolean
  /** 底部浮动操作条占的高度（像素），适应窗口时让开 */
  insetBottom?: number
}>(), {
  insetBottom: 0,
  focus: null, pick: 'plot', selectedPlots: () => [], selectedBlocks: () => [], selectedCells: () => [],
  links: () => [], devices: () => [], rotation: 0, locked: false, panning: false, rulers: false, draggable: false,
})

const emit = defineEmits<{
  'update:selectedPlots': [ids: number[]]
  'update:selectedBlocks': [ids: string[]]
  'update:selectedCells': [keys: string[]]
  'update:rotation': [deg: number]
  open: [plotId: number]
  enter: [blockId: string]
  contextmenu: [target: { plotId?: number; blockId?: string; cell?: string }, x: number, y: number]
  /** 拖动特殊区块松手：x、y 是在槽位里的新偏移 */
  moveFit: [blockId: string, x: number, y: number]
  /** 点了标尺上的第 index 行（列） */
  track: [axis: 'row' | 'col', index: number, additive: boolean]
  zoom: [percent: number]
  /** 鼠标在布局里的位置（单位同布局），移出画布时为 null */
  pointer: [p: { x: number; y: number } | null]
}>()

const FONT = "'Microsoft YaHei UI', 'Microsoft YaHei', 'PingFang SC', 'Noto Sans CJK SC', sans-serif"
const INK = '#1f2a22'
const FOREST = '#1c4231'
const WHEAT = '#d9a748'
const ROAD = '#d9cfb9'
const ROAD_LINE = '#f6f0e2'
const RULER = 34

// ---------- 画布大小 ----------

const wrap = ref<HTMLDivElement | null>(null)
const stageRef = ref<{ getNode: () => Konva.Stage } | null>(null)
const size = ref({ w: 800, h: 600 })
let observer: ResizeObserver | null = null

onMounted(() => {
  observer = new ResizeObserver(() => {
    const el = wrap.value
    if (!el) return
    const w = Math.max(200, el.clientWidth)
    const h = Math.max(200, el.clientHeight)
    if (w === size.value.w && h === size.value.h) return
    size.value = { w, h }
    if (props.locked || !touched) fit()
  })
  if (wrap.value) observer.observe(wrap.value)
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
  window.addEventListener('mouseup', onWindowUp)
  window.addEventListener('mousemove', onWindowMove)
  nextTick(fit)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  window.removeEventListener('mouseup', onWindowUp)
  window.removeEventListener('mousemove', onWindowMove)
})

// ---------- 视图：屏幕 = pan + 旋转(scale × (p − origin)) ----------

const scale = ref(20)
const pan = ref({ x: 400, y: 300 })
const origin = ref({ x: 0, y: 0 })
/** 用户自己缩放、拖动过：窗口变大小时不再自动适应 */
let touched = false
let fitScale = 20

const focusBlock = computed(() => (props.focus ? props.layout.blocks.find((b) => b.id === props.focus) ?? null : null))

/** 现在看的范围（单位同布局） */
const contentRect = computed<Rect>(() => {
  if (focusBlock.value) return blockSlot(props.layout, focusBlock.value)
  const s = fieldSize(props.layout)
  return { x: 0, y: 0, w: s.w, h: s.h }
})

const rad = computed(() => (props.rotation * Math.PI) / 180)

function toScreen(p: { x: number; y: number }) {
  const dx = (p.x - origin.value.x) * scale.value
  const dy = (p.y - origin.value.y) * scale.value
  const c = Math.cos(rad.value)
  const s = Math.sin(rad.value)
  return { x: pan.value.x + dx * c - dy * s, y: pan.value.y + dx * s + dy * c }
}

function toContent(p: { x: number; y: number }) {
  const dx = p.x - pan.value.x
  const dy = p.y - pan.value.y
  const c = Math.cos(-rad.value)
  const s = Math.sin(-rad.value)
  return { x: origin.value.x + (dx * c - dy * s) / scale.value, y: origin.value.y + (dx * s + dy * c) / scale.value }
}

/** 正好放下现在看的范围（旋转后的外框），四周留出标尺和方位字的地方 */
function fit() {
  const r = contentRect.value
  const margin = props.rulers || focusBlock.value ? RULER + 30 : 46
  const c = Math.abs(Math.cos(rad.value))
  const s = Math.abs(Math.sin(rad.value))
  const bw = r.w * c + r.h * s
  const bh = r.w * s + r.h * c
  const availH = size.value.h - props.insetBottom
  const k = Math.min((size.value.w - margin * 2) / Math.max(bw, 1e-6), (availH - margin * 2) / Math.max(bh, 1e-6))
  fitScale = Math.max(0.01, k)
  scale.value = fitScale
  origin.value = { x: r.x + r.w / 2, y: r.y + r.h / 2 }
  pan.value = { x: size.value.w / 2, y: availH / 2 }
  touched = false
  emit('zoom', 100)
}

function zoomAt(factor: number, at?: { x: number; y: number }) {
  const p = at ?? { x: size.value.w / 2, y: size.value.h / 2 }
  const before = toContent(p)
  const next = Math.min(fitScale * 30, Math.max(fitScale * 0.2, scale.value * factor))
  scale.value = next
  // 让鼠标下面的那一点不动
  const after = toScreen(before)
  pan.value = { x: pan.value.x + p.x - after.x, y: pan.value.y + p.y - after.y }
  touched = true
  emit('zoom', Math.round((next / fitScale) * 100))
}

function zoomBy(factor: number) {
  if (!props.locked) zoomAt(factor)
}

/** 绕画布中心转到 deg 度 */
function rotateTo(deg: number) {
  const center = { x: size.value.w / 2, y: size.value.h / 2 }
  const keep = toContent(center)
  const norm = ((Math.round(deg * 10) / 10) % 360 + 360) % 360
  emit('update:rotation', norm)
  nextTick(() => {
    const after = toScreen(keep)
    pan.value = { x: pan.value.x + center.x - after.x, y: pan.value.y + center.y - after.y }
    if (props.locked) fit()
  })
}

watch(() => props.focus, () => nextTick(fit))
watch(() => props.locked, (v) => v && nextTick(fit))
watch(() => props.rotation, () => { if (props.locked) nextTick(fit) })
// 布局大小变了（改行高列宽、加删行列）时重新放好
watch(() => `${contentRect.value.w}x${contentRect.value.h}`, () => { if (!touched || props.locked) nextTick(fit) })

const groupConfig = computed(() => ({
  x: pan.value.x, y: pan.value.y, rotation: props.rotation, scaleX: scale.value, scaleY: scale.value,
  offsetX: origin.value.x, offsetY: origin.value.y,
}))

/** 屏幕上 px 像素在布局里是多长 */
const px = (v: number) => v / scale.value

// ---------- 要画的东西 ----------

interface BlockShape {
  block: LayoutBlock
  slot: Rect
  rect: Rect
  span: ReturnType<typeof blockSpan>
  cells: { key: string; rect: Rect; plotId: number | null }[]
}

/** 拖动中的特殊区块（还没松手） */
const dragFit = ref<{ id: string; x: number; y: number } | null>(null)

const shapes = computed<BlockShape[]>(() => {
  const list = focusBlock.value ? [focusBlock.value] : props.layout.blocks
  return list.map((b0) => {
    const b = dragFit.value?.id === b0.id && b0.fit ? { ...b0, fit: { ...b0.fit, x: dragFit.value.x, y: dragFit.value.y } } : b0
    return {
      block: b0, slot: blockSlot(props.layout, b), rect: blockRect(props.layout, b), span: blockSpan(props.layout, b),
      cells: innerCells(props.layout, b),
    }
  })
})

const selPlots = computed(() => new Set(props.selectedPlots))
const selBlocks = computed(() => new Set(props.selectedBlocks))
const selCells = computed(() => new Set(props.selectedCells))

/**
 * 文字保持正着：画在格子中心，反着转回来；转了 90° 时宽高对调。
 * 格子又窄又长（小麦小区常见 2 m × 6.67 m）、横着写不下时，顺着长边竖着写
 */
function uprightBox(r: Rect) {
  const quarter = Math.abs(((props.rotation % 180) + 180) % 180 - 90) < 20
  let w = quarter ? r.h : r.w
  let h = quarter ? r.w : r.h
  let rotation = -props.rotation
  if (w * scale.value < 40 && h > w * 1.8) {
    ;[w, h] = [h, w]
    rotation -= 90
  }
  return { x: r.x + r.w / 2, y: r.y + r.h / 2, w, h, offsetX: w / 2, offsetY: h / 2, rotation }
}

/** 格子在屏幕上够大才写字：写几行 */
function lineBudget(r: Rect): number {
  const box = uprightBox(r)
  const w = box.w * scale.value
  const h = box.h * scale.value
  if (w < 34 || h < 18) return 0
  return Math.max(1, Math.min(4, Math.floor((h - 6) / 16)))
}

function cellText(plotId: number | null, r: Rect): string {
  if (plotId == null) return '新小区'
  const c = props.cells.get(plotId)
  if (!c) return ''
  return c.lines.slice(0, lineBudget(r)).join('\n')
}

const GAP_PX = 1.5

/** Konva 的矩形用 width、height */
const box = (r: Rect) => ({ x: r.x, y: r.y, width: r.w, height: r.h })

function inset(r: Rect, pxGap: number): Rect {
  const g = Math.min(px(pxGap), r.w / 4, r.h / 4)
  return { x: r.x + g, y: r.y + g, w: r.w - g * 2, h: r.h - g * 2 }
}

// 保护区、道路的底纹（生图后做成的无缝小图，和底色混过，不抢小区的颜色）
function loadImage(url: string) {
  const r = ref<HTMLImageElement | undefined>(undefined)
  if (typeof Image !== 'undefined') {
    const img = new Image()
    img.onload = () => (r.value = img)
    img.src = url
  }
  return r
}

const guardTile = loadImage(guardTileUrl)
const roadTile = loadImage(roadTileUrl)

/** 道路：土路底纹 */
function roadFill() {
  return roadTile.value
    ? { fillPatternImage: roadTile.value, fillPatternRepeat: 'repeat', fillPatternScaleX: px(0.6), fillPatternScaleY: px(0.6) }
    : { fill: ROAD }
}

function kindFill(kind: LayoutBlock['kind']) {
  if (kind === 'GUARD') {
    return guardTile.value
      ? { fillPatternImage: guardTile.value, fillPatternRepeat: 'repeat', fillPatternScaleX: px(0.75), fillPatternScaleY: px(0.75) }
      : { fill: '#d6e3c6' }
  }
  if (kind === 'ROAD') return roadFill()
  if (kind === 'BLANK') return { fill: '#eee6d4' }
  return { fill: '#e7dcc2' }
}

/** 道路中间一条虚线，顺着长的方向 */
function roadLine(r: Rect): number[] {
  return r.w >= r.h ? [r.x + px(6), r.y + r.h / 2, r.x + r.w - px(6), r.y + r.h / 2]
    : [r.x + r.w / 2, r.y + px(6), r.x + r.w / 2, r.y + r.h - px(6)]
}

/** 槽位里特殊区块以外的部分（道路），最多四块 */
function slotRoads(s: BlockShape): Rect[] {
  if (!s.block.fit) return []
  const { slot: o, rect: i } = s
  const out: Rect[] = []
  if (i.y > o.y) out.push({ x: o.x, y: o.y, w: o.w, h: i.y - o.y })
  if (i.y + i.h < o.y + o.h) out.push({ x: o.x, y: i.y + i.h, w: o.w, h: o.y + o.h - i.y - i.h })
  if (i.x > o.x) out.push({ x: o.x, y: i.y, w: i.x - o.x, h: i.h })
  if (i.x + i.w < o.x + o.w) out.push({ x: i.x + i.w, y: i.y, w: o.x + o.w - i.x - i.w, h: i.h })
  return out
}

// 对照虚线
const cellRectByPlot = computed(() => {
  const m = new Map<number, Rect>()
  for (const s of shapes.value) for (const c of s.cells) if (c.plotId != null) m.set(c.plotId, c.rect)
  return m
})

const linkLines = computed(() =>
  props.links.flatMap((link) => link.segments.flatMap((seg, i) => {
    const a = cellRectByPlot.value.get(seg.from)
    const b = cellRectByPlot.value.get(seg.to)
    return a && b ? [{ key: `${link.id}-${i}`, points: [a.x + a.w / 2, a.y + a.h / 2, b.x + b.w / 2, b.y + b.h / 2] }] : []
  })))

const linkRings = computed(() => {
  const rings = new Map<number, boolean>()
  for (const l of props.links) {
    for (const s of l.segments) rings.set(s.to, rings.get(s.to) ?? false)
    for (const id of l.controlPlotIds) rings.set(id, true)
  }
  return [...rings].flatMap(([id, control]) => {
    const r = cellRectByPlot.value.get(id)
    return r ? [{ id, control, r }] : []
  })
})

// ---------- 标尺 ----------

interface Tick {
  key: string
  axis: 'row' | 'col'
  index: number
  rect: Rect
  label: string
  /** 地方不够时只写这个 */
  short?: string
  selected: boolean
}

/** 标尺上的字：放得下写全（序号 · 大小），放不下只写序号或大小 */
function tickLabel(t: Tick): string {
  const box = uprightBox(t.rect)
  const room = box.w * scale.value
  const need = t.label.length * 7 + 8
  return room >= need || !t.short ? (room >= 18 ? t.label : '') : room >= t.short.length * 7 + 4 ? t.short : ''
}

const rulerTicks = computed<Tick[]>(() => {
  const showSizes = props.rulers
  const out: Tick[] = []
  const u = props.layout.unit
  const gap = px(RULER) * 0.82
  const fb = focusBlock.value
  if (fb) {
    if (!fb.inner) return []
    const box = blockRect(props.layout, fb)
    const sizes = innerSizes(props.layout, fb)
    let x = box.x
    sizes.cols.forEach((w, i) => {
      out.push({
        key: `c${i}`, axis: 'col', index: i, rect: { x, y: box.y - gap, w, h: gap * 0.8 },
        label: showSizes ? `${i + 1} · ${fmt(w, u)}` : String(i + 1), short: String(i + 1), selected: false,
      })
      x += w
    })
    let y = box.y
    sizes.rows.forEach((h, i) => {
      out.push({
        key: `r${i}`, axis: 'row', index: i, rect: { x: box.x - gap, y, w: gap * 0.8, h },
        label: showSizes ? `${i + 1} · ${fmt(h, u)}` : String(i + 1), short: String(i + 1), selected: false,
      })
      y += h
    })
    return out
  }
  if (!showSizes) return []
  let x = 0
  props.layout.cols.forEach((w, i) => {
    out.push({ key: `c${i}`, axis: 'col', index: i, rect: { x, y: -gap, w, h: gap * 0.8 }, label: fmt(w, u), selected: false })
    x += w
  })
  let y = 0
  props.layout.rows.forEach((h, i) => {
    out.push({ key: `r${i}`, axis: 'row', index: i, rect: { x: -gap, y, w: gap * 0.8, h }, label: fmt(h, u), selected: false })
    y += h
  })
  return out
})

// ---------- 鼠标 ----------

const band = ref<{ x1: number; y1: number; x2: number; y2: number } | null>(null)
let press: { x: number; y: number; button: number; target: string; additive: boolean } | null = null
let mode: 'none' | 'select' | 'pan' | 'drag' = 'none'
let panStart: { x: number; y: number; px: number; py: number } | null = null
let dragStart: { id: string; fx: number; fy: number; cx: number; cy: number } | null = null
const space = ref(false)

function onKeyDown(e: KeyboardEvent) {
  if (e.code === 'Space' && !isTyping(e)) {
    space.value = true
    if (wrap.value?.matches(':hover')) e.preventDefault()
  }
}

function onKeyUp(e: KeyboardEvent) {
  if (e.code === 'Space') space.value = false
}

function isTyping(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)
}

function stagePointer(): { x: number; y: number } | null {
  return stageRef.value?.getNode().getPointerPosition() ?? null
}

function targetName(e: KonvaEventObject<MouseEvent>): string {
  let n: Konva.Node | null = e.target
  while (n && !n.name() && n.getParent()) n = n.getParent() as Konva.Node | null
  return n?.name() ?? ''
}

function onDown(e: KonvaEventObject<MouseEvent>) {
  const p = stagePointer()
  if (!p) return
  hover.value = null
  const target = targetName(e)
  const additive = e.evt.ctrlKey || e.evt.metaKey || e.evt.shiftKey
  press = { x: p.x, y: p.y, button: e.evt.button, target, additive }
  if (!props.locked && (e.evt.button === 1 || ((space.value || props.panning) && e.evt.button === 0))) {
    e.evt.preventDefault()
    mode = 'pan'
    panStart = { x: p.x, y: p.y, px: pan.value.x, py: pan.value.y }
    return
  }
  if (e.evt.button !== 0) {
    mode = 'none'
    return
  }
  // 拖动选中的特殊区块
  if (props.draggable && target.startsWith('block:')) {
    const id = target.slice(6)
    const b = props.layout.blocks.find((x) => x.id === id)
    if (b?.fit && selBlocks.value.has(id)) {
      const c = toContent(p)
      dragStart = { id, fx: b.fit.x, fy: b.fit.y, cx: c.x, cy: c.y }
      mode = 'drag'
      return
    }
  }
  mode = 'select'
}

function onMove() {
  const p = stagePointer()
  if (!p) return
  if (mode === 'pan' && panStart) {
    pan.value = { x: panStart.px + p.x - panStart.x, y: panStart.py + p.y - panStart.y }
    touched = true
    return
  }
  if (mode === 'drag' && dragStart) {
    const c = toContent(p)
    const b = props.layout.blocks.find((x) => x.id === dragStart!.id)!
    const slot = blockSlot(props.layout, b)
    const fitW = b.fit!.w
    const fitH = b.fit!.h
    const x = Math.min(Math.max(dragStart.fx + c.x - dragStart.cx, 0), slot.w - fitW)
    const y = Math.min(Math.max(dragStart.fy + c.y - dragStart.cy, 0), slot.h - fitH)
    dragFit.value = { id: dragStart.id, x: round(x, 3), y: round(y, 3) }
    return
  }
  if (mode === 'select' && press && Math.hypot(p.x - press.x, p.y - press.y) > 5) {
    band.value = { x1: press.x, y1: press.y, x2: p.x, y2: p.y }
    return
  }
  if (mode === 'none') updateHover(p)
  emit('pointer', toContent(p))
}

/** 鼠标拖到画布外面松开也要收尾 */
function onWindowMove(e: MouseEvent) {
  if (mode === 'pan' && panStart && wrap.value) {
    const r = wrap.value.getBoundingClientRect()
    pan.value = { x: panStart.px + e.clientX - r.left - panStart.x, y: panStart.py + e.clientY - r.top - panStart.y }
  }
}

function onWindowUp() {
  if (mode === 'drag') finishDrag()
  if (mode !== 'none') {
    mode = 'none'
    band.value = null
    press = null
  }
}

function finishDrag() {
  const d = dragFit.value
  dragStart = null
  mode = 'none'
  if (d) emit('moveFit', d.id, d.x, d.y)
  dragFit.value = null
}

function onUp(e: KonvaEventObject<MouseEvent>) {
  const p = press
  const m = mode
  press = null
  mode = 'none'
  if (m === 'drag') {
    finishDrag()
    return
  }
  if (m !== 'select' || !p) return
  const additive = p.additive || e.evt.ctrlKey || e.evt.metaKey || e.evt.shiftKey
  if (band.value) {
    boxSelect(band.value, additive)
    band.value = null
    return
  }
  click(p.target, additive)
}

function toggle<T>(list: T[], v: T, additive: boolean): T[] {
  if (!additive) return [v]
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v]
}

function blockPlots(id: string): number[] {
  const b = props.layout.blocks.find((x) => x.id === id)
  return Object.values(b?.inner?.plots ?? {})
}

function click(target: string, additive: boolean) {
  const [kind, rest] = [target.slice(0, target.indexOf(':')), target.slice(target.indexOf(':') + 1)]
  if (kind === 'tick') {
    const [axis, index] = rest.split(':')
    emit('track', axis as 'row' | 'col', Number(index), additive)
    if (props.pick === 'plot' && focusBlock.value) {
      selectTrackPlots(axis as 'row' | 'col', Number(index), additive)
    }
    return
  }
  if (props.pick === 'plot') {
    if (kind === 'plot') emit('update:selectedPlots', toggle(props.selectedPlots, Number(rest), additive))
    else if (kind === 'label' || (kind === 'block' && !focusBlock.value)) {
      const ids = blockPlots(rest)
      emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
    } else if (!additive) emit('update:selectedPlots', [])
    return
  }
  if (props.pick === 'block') {
    const id = kind === 'block' || kind === 'label' ? rest : kind === 'plot' || kind === 'cell' ? blockOfTarget(target) : null
    if (id) emit('update:selectedBlocks', toggle(props.selectedBlocks, id, additive))
    else if (!additive) emit('update:selectedBlocks', [])
    return
  }
  // cell
  if (kind === 'plot' || kind === 'cell') {
    const key = cellKeyOfTarget(target)
    if (key) emit('update:selectedCells', toggle(props.selectedCells, key, additive))
  } else if (!additive) emit('update:selectedCells', [])
}

function selectTrackPlots(axis: 'row' | 'col', index: number, additive: boolean) {
  const b = focusBlock.value
  if (!b?.inner) return
  const ids = anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges)
    .filter((s) => (axis === 'row' ? index >= s.r && index < s.r + s.rs : index >= s.c && index < s.c + s.cs))
    .map((s) => b.inner!.plots[keyOf(s.r, s.c)])
    .filter((v): v is number => v != null)
  emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
}

/** plot:12 或 cell:B3:0,1 → 区块 id */
function blockOfTarget(target: string): string | null {
  if (target.startsWith('cell:')) return target.split(':')[1] ?? null
  if (target.startsWith('plot:')) {
    const id = Number(target.slice(5))
    return props.layout.blocks.find((b) => Object.values(b.inner?.plots ?? {}).includes(id))?.id ?? null
  }
  return null
}

function cellKeyOfTarget(target: string): string | null {
  if (target.startsWith('cell:')) return target.split(':')[2] ?? null
  if (target.startsWith('plot:')) {
    const id = Number(target.slice(5))
    const b = focusBlock.value
    const hit = Object.entries(b?.inner?.plots ?? {}).find(([, v]) => v === id)
    return hit?.[0] ?? null
  }
  return null
}

function boxSelect(b: { x1: number; y1: number; x2: number; y2: number }, additive: boolean) {
  const [l, r] = [Math.min(b.x1, b.x2), Math.max(b.x1, b.x2)]
  const [t, btm] = [Math.min(b.y1, b.y2), Math.max(b.y1, b.y2)]
  const hit = (rect: Rect) => {
    const c = toScreen({ x: rect.x + rect.w / 2, y: rect.y + rect.h / 2 })
    return c.x >= l && c.x <= r && c.y >= t && c.y <= btm
  }
  if (props.pick === 'plot') {
    const ids = shapes.value.flatMap((s) => s.cells.filter((c) => c.plotId != null && hit(c.rect)).map((c) => c.plotId!))
    emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
  } else if (props.pick === 'block') {
    const ids = shapes.value.filter((s) => hit(s.rect)).map((s) => s.block.id)
    emit('update:selectedBlocks', additive ? [...new Set([...props.selectedBlocks, ...ids])] : ids)
  } else {
    const keys = shapes.value.flatMap((s) => s.cells.filter((c) => hit(c.rect)).map((c) => c.key))
    emit('update:selectedCells', additive ? [...new Set([...props.selectedCells, ...keys])] : keys)
  }
}

function onDblClick(e: KonvaEventObject<MouseEvent>) {
  if (props.panning || space.value) return
  const target = targetName(e)
  if (target.startsWith('label:')) {
    emit('enter', target.slice(6))
    return
  }
  if (props.pick === 'plot' && target.startsWith('plot:')) {
    emit('open', Number(target.slice(5)))
    return
  }
  if (props.pick === 'block' && !focusBlock.value) {
    const id = target.startsWith('block:') ? target.slice(6) : blockOfTarget(target)
    if (id) emit('enter', id)
  }
}

function onContextMenu(e: KonvaEventObject<MouseEvent>) {
  e.evt.preventDefault()
  const target = targetName(e)
  if (target.startsWith('plot:')) {
    const id = Number(target.slice(5))
    if (props.pick === 'plot' && !selPlots.value.has(id)) emit('update:selectedPlots', [id])
    emit('contextmenu', { plotId: id, blockId: blockOfTarget(target) ?? undefined }, e.evt.clientX, e.evt.clientY)
  } else if (target.startsWith('block:') || target.startsWith('label:')) {
    const id = target.slice(target.indexOf(':') + 1)
    if (props.pick === 'block' && !selBlocks.value.has(id)) emit('update:selectedBlocks', [id])
    emit('contextmenu', { blockId: id }, e.evt.clientX, e.evt.clientY)
  } else if (target.startsWith('cell:')) {
    emit('contextmenu', { blockId: blockOfTarget(target) ?? undefined, cell: cellKeyOfTarget(target) ?? undefined },
      e.evt.clientX, e.evt.clientY)
  }
}

function onWheel(e: KonvaEventObject<WheelEvent>) {
  e.evt.preventDefault()
  if (props.locked) return
  const p = stagePointer() ?? undefined
  if (e.evt.shiftKey) {
    rotateTo(props.rotation + (e.evt.deltaY > 0 ? 5 : -5))
    return
  }
  zoomAt(e.evt.deltaY < 0 ? 1.12 : 1 / 1.12, p)
}

// ---------- 鼠标停留的说明 ----------

const hover = ref<{ text: string; x: number; y: number } | null>(null)

function updateHover(p: { x: number; y: number }) {
  const stage = stageRef.value?.getNode()
  const shape = stage?.getIntersection(p)
  let n: Konva.Node | null = shape ?? null
  while (n && !n.name() && n.getParent()) n = n.getParent() as Konva.Node | null
  const name = n?.name() ?? ''
  let text = ''
  if (name.startsWith('plot:')) text = props.cells.get(Number(name.slice(5)))?.tooltip ?? ''
  else if (name.startsWith('block:') || name.startsWith('label:')) text = blockTip(name.slice(name.indexOf(':') + 1))
  if (!text) {
    hover.value = null
    return
  }
  hover.value = {
    text, x: p.x > size.value.w - 230 ? p.x - 220 : p.x + 16, y: p.y > size.value.h - 120 ? p.y - 110 : p.y + 16,
  }
}

function blockTip(id: string): string {
  const b = props.layout.blocks.find((x) => x.id === id)
  if (!b) return ''
  const u = props.layout.unit
  const slot = blockSlot(props.layout, b)
  const r = blockRect(props.layout, b)
  const lines = [`${b.name}`, `${fmt(r.w, u)} × ${fmt(r.h, u)}`]
  if (b.fit) lines.push(`特殊区块：原来 ${fmt(slot.w, u)} × ${fmt(slot.h, u)}，四周是道路`)
  if (b.inner) {
    const n = anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges).length
    lines.push(`${b.inner.rows.length} 行 × ${b.inner.cols.length} 列，${n} 个小区`)
  }
  return lines.join('\n')
}

function onLeave() {
  hover.value = null
  emit('pointer', null)
}

// ---------- 导出 ----------

const exporting = ref(false)

/** 导出 jpg 种植图：先适应窗口，白底 */
async function exportJpeg(filename: string) {
  const stage = stageRef.value?.getNode()
  if (!stage) return
  hover.value = null
  fit()
  exporting.value = true
  await nextTick()
  try {
    saveFile(stage.toDataURL({ mimeType: 'image/jpeg', quality: 0.92, pixelRatio: 2 }), filename)
  } finally {
    exporting.value = false
  }
}

/** 一个点（布局里的坐标）在画布上的位置，给外面放方位字、指北针用 */
function screenOf(p: { x: number; y: number }) {
  return toScreen(p)
}

defineExpose({ fit, zoomBy, rotateTo, exportJpeg, screenOf, contentRect })

// 开发时给自动化测试用：布局坐标 → 画布上的位置
if (import.meta.env.DEV) {
  ;(window as unknown as { __fieldCanvas?: unknown }).__fieldCanvas = { screenOf }
}

const cursor = computed(() => {
  if (props.locked) return 'default'
  if (space.value || props.panning) return 'grab'
  return 'default'
})

const blockStroke = (s: BlockShape) => {
  if (selBlocks.value.has(s.block.id)) return { stroke: WHEAT, strokeWidth: 3 }
  return { stroke: s.block.kind === 'PLANT' ? 'rgba(28, 66, 49, 0.55)' : 'rgba(90, 70, 30, 0.25)', strokeWidth: s.block.kind === 'PLANT' ? 1.6 : 1 }
}

const totalSize = computed(() => {
  const s = fieldSize(props.layout)
  return { w: sum(props.layout.cols), h: sum(props.layout.rows), area: s.w * s.h }
})

const fieldRect = computed(() => trackRect(props.layout.rows, props.layout.cols, {
  r: 0, c: 0, rs: props.layout.rows.length, cs: props.layout.cols.length,
}))
</script>

<template>
  <div ref="wrap" class="field-canvas" :style="{ cursor }" @contextmenu.prevent>
    <v-stage
      ref="stageRef"
      :config="{ width: size.w, height: size.h }"
      @mousedown="onDown"
      @mousemove="onMove"
      @mouseup="onUp"
      @mouseleave="onLeave"
      @dblclick="onDblClick"
      @contextmenu="onContextMenu"
      @wheel="onWheel"
    >
      <v-layer>
        <v-rect v-if="exporting" :config="{ x: 0, y: 0, width: size.w, height: size.h, fill: '#ffffff', listening: false }" />
        <v-group :config="groupConfig">
          <!-- 整块地的底和外框 -->
          <v-rect
            v-if="!focusBlock"
            :config="{
              ...box(fieldRect), fill: '#cdbf9f', stroke: FOREST, strokeWidth: 2.5, strokeScaleEnabled: false,
              shadowColor: '#3b2a0c', shadowBlur: 18, shadowOpacity: 0.18, shadowOffsetY: 4, listening: false,
            }"
          />

          <template v-for="s in shapes" :key="s.block.id">
            <!-- 特殊区块：槽位里其余部分是道路 -->
            <v-rect
              v-for="(r, i) in slotRoads(s)"
              :key="`road-${s.block.id}-${i}`"
              :config="{ ...box(r), ...roadFill(), listening: false }"
            />
            <v-line
              v-for="(r, i) in slotRoads(s)"
              :key="`roadline-${s.block.id}-${i}`"
              :config="{ points: roadLine(r), stroke: ROAD_LINE, strokeWidth: 1.5, dash: [8, 6], strokeScaleEnabled: false, listening: false }"
            />
            <v-rect
              v-if="s.block.fit"
              :config="{
                ...box(s.slot), stroke: 'rgba(28, 66, 49, 0.35)', strokeWidth: 1, dash: [4, 4], strokeScaleEnabled: false, listening: false,
              }"
            />

            <!-- 区块 -->
            <v-rect
              :config="{
                ...box(s.rect), ...kindFill(s.block.kind), ...blockStroke(s), strokeScaleEnabled: false,
                name: `block:${s.block.id}`,
              }"
            />
            <v-line
              v-if="s.block.kind === 'ROAD'"
              :config="{ points: roadLine(s.rect), stroke: ROAD_LINE, strokeWidth: 1.5, dash: [8, 6], strokeScaleEnabled: false, listening: false }"
            />
            <v-text
              v-if="s.block.kind !== 'PLANT' && lineBudget(s.rect) > 0"
              :config="{
                ...uprightBox(s.rect), width: uprightBox(s.rect).w, height: uprightBox(s.rect).h,
                text: s.block.name, align: 'center', verticalAlign: 'middle', fontSize: px(12), fill: '#7a6d52',
                fontFamily: FONT, listening: false,
              }"
            />

            <!-- 小区 -->
            <template v-for="c in s.cells" :key="`${s.block.id}:${c.key}`">
              <v-rect
                :config="{
                  ...box(inset(c.rect, GAP_PX)),
                  fill: c.plotId != null ? (cells.get(c.plotId)?.fill ?? '#f4eedc') : '#fbf7ee',
                  opacity: c.plotId != null && cells.get(c.plotId)?.muted ? 0.75 : 1,
                  stroke: (pick === 'plot' && c.plotId != null && selPlots.has(c.plotId)) || (pick === 'cell' && selCells.has(c.key))
                    ? FOREST : c.plotId == null ? 'rgba(46,107,79,0.55)' : 'rgba(90, 70, 30, 0.18)',
                  strokeWidth: (pick === 'plot' && c.plotId != null && selPlots.has(c.plotId)) || (pick === 'cell' && selCells.has(c.key)) ? 2.5 : 1,
                  dash: c.plotId == null ? [4, 3] : undefined,
                  strokeScaleEnabled: false,
                  cornerRadius: px(3),
                  name: pick === 'cell' || c.plotId == null ? `cell:${s.block.id}:${c.key}` : `plot:${c.plotId}`,
                }"
              />
              <v-rect
                v-if="(pick === 'plot' && c.plotId != null && selPlots.has(c.plotId)) || (pick === 'cell' && selCells.has(c.key))"
                :config="{ ...box(inset(c.rect, GAP_PX)), fill: 'rgba(226, 182, 90, 0.28)', cornerRadius: px(3), listening: false }"
              />
              <v-text
                v-if="lineBudget(c.rect) > 0"
                :config="{
                  ...uprightBox(c.rect), width: uprightBox(c.rect).w - px(6), height: uprightBox(c.rect).h - px(4),
                  offsetX: (uprightBox(c.rect).w - px(6)) / 2, offsetY: (uprightBox(c.rect).h - px(4)) / 2,
                  text: cellText(c.plotId, c.rect), align: 'center', verticalAlign: 'middle',
                  fontSize: px(12), lineHeight: 1.3, fill: c.plotId == null ? '#2e6b4f' : INK, fontFamily: FONT,
                  wrap: 'none', ellipsis: true, listening: false,
                }"
              />
              <v-text
                v-if="c.plotId != null && cells.get(c.plotId)?.badge && lineBudget(c.rect) > 0"
                :config="{
                  x: c.rect.x + c.rect.w - px(4), y: c.rect.y + px(3), offsetX: px(30), width: px(30),
                  text: cells.get(c.plotId)!.badge, align: 'right', fontSize: px(10), fontStyle: 'bold',
                  fill: cells.get(c.plotId)!.badge!.includes('!') ? '#b0543a' : '#8a6118', fontFamily: FONT, listening: false,
                }"
              />
              <v-circle
                v-if="c.plotId != null && cells.get(c.plotId)?.marker"
                :config="{
                  x: c.rect.x + Math.min(px(9), c.rect.w / 4), y: c.rect.y + c.rect.h - Math.min(px(9), c.rect.h / 4),
                  radius: Math.min(px(3.5), c.rect.w / 6), fill: '#2e6b4f', stroke: '#ffffff', strokeWidth: 1.2,
                  strokeScaleEnabled: false, listening: false,
                }"
              />
            </template>

            <!-- 选中的区块：淡金色罩一层 -->
            <v-rect
              v-if="selBlocks.has(s.block.id)"
              :config="{ ...box(s.rect), fill: 'rgba(226, 182, 90, 0.22)', listening: false }"
            />
            <v-rect
              v-if="draggable && s.block.fit && selBlocks.has(s.block.id)"
              :config="{ ...box(s.rect), stroke: WHEAT, strokeWidth: 2, dash: [6, 4], strokeScaleEnabled: false, listening: false }"
            />

            <!-- 区块名称牌：整块地视图里才有，双击进入区块 -->
            <v-label
              v-if="!focusBlock && s.block.kind === 'PLANT' && s.rect.w * scale > 46"
              :config="{
                x: s.rect.x + px(4), y: s.rect.y + px(4), scaleX: px(1), scaleY: px(1), rotation: -rotation,
                name: `label:${s.block.id}`,
              }"
            >
              <v-tag
                :config="{
                  fill: selBlocks.has(s.block.id) ? WHEAT : 'rgba(28, 66, 49, 0.86)', cornerRadius: 5,
                  shadowColor: '#000', shadowBlur: 4, shadowOpacity: 0.15,
                }"
              />
              <v-text
                :config="{
                  text: s.block.name, fontSize: 11, fontStyle: 'bold', padding: 4,
                  fill: selBlocks.has(s.block.id) ? '#3b2a0c' : '#fbf3df', fontFamily: FONT,
                }"
              />
            </v-label>
          </template>

          <!-- 对照关系 -->
          <v-rect
            v-for="r in linkRings"
            :key="'ring' + r.id"
            :config="{
              ...box(inset(r.r, 2)), stroke: '#c0562f', strokeWidth: r.control ? 2.5 : 1.5, dash: r.control ? undefined : [5, 4],
              strokeScaleEnabled: false, listening: false, cornerRadius: px(3),
            }"
          />
          <v-line
            v-for="l in linkLines"
            :key="l.key"
            :config="{ points: l.points, stroke: '#c0562f', strokeWidth: 2, dash: [8, 6], lineCap: 'round', strokeScaleEnabled: false, listening: false }"
          />

          <!-- 传感器 -->
          <template v-if="!focusBlock">
            <v-circle
              v-for="d in devices"
              :key="'dev' + d.id"
              :config="{ x: d.x, y: d.y, radius: px(7), fill: d.color, stroke: '#ffffff', strokeWidth: 2, strokeScaleEnabled: false, listening: false }"
            />
          </template>

          <!-- 标尺 -->
          <template v-for="t in rulerTicks" :key="t.key">
            <v-rect
              :config="{
                ...box(t.rect), fill: 'rgba(255, 253, 248, 0.92)', stroke: 'rgba(90, 70, 30, 0.25)', strokeWidth: 1,
                strokeScaleEnabled: false, cornerRadius: px(4), name: `tick:${t.axis}:${t.index}`,
              }"
            />
            <v-text
              :config="{
                ...uprightBox(t.rect), width: uprightBox(t.rect).w, height: uprightBox(t.rect).h,
                text: tickLabel(t), align: 'center', verticalAlign: 'middle', fontSize: px(11), fill: '#6b6450',
                fontFamily: FONT, wrap: 'none', ellipsis: true, listening: false,
              }"
            />
          </template>
        </v-group>

        <v-rect
          v-if="band"
          :config="{
            x: Math.min(band.x1, band.x2), y: Math.min(band.y1, band.y2),
            width: Math.abs(band.x2 - band.x1), height: Math.abs(band.y2 - band.y1),
            fill: 'rgba(46, 107, 79, 0.10)', stroke: '#2e6b4f', strokeWidth: 1.5, dash: [5, 4], listening: false,
          }"
        />
      </v-layer>
    </v-stage>
    <div v-if="hover" class="tip" :style="{ left: hover.x + 'px', top: hover.y + 'px' }">{{ hover.text }}</div>
    <div class="scale-note num">{{ fmt(totalSize.w, layout.unit) }} × {{ fmt(totalSize.h, layout.unit) }}</div>
    <slot />
  </div>
</template>

<style scoped>
.field-canvas {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  user-select: none;
}

.tip {
  position: absolute;
  z-index: 3;
  max-width: 260px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(24, 58, 43, 0.95);
  color: #f6efdc;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-line;
  pointer-events: none;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.2);
}

.scale-note {
  position: absolute;
  left: 12px;
  bottom: 10px;
  font-size: 11px;
  color: var(--ink-3);
  background: rgba(255, 253, 248, 0.8);
  border-radius: 6px;
  padding: 2px 8px;
  pointer-events: none;
}
</style>
