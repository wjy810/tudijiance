<script setup lang="ts">
// 平面图（俯视）：按实际尺寸画实验田的区块、道路、保护区、空地和区块里的小区。
//
// 为了快，这里不用 Vue 模板画图形，而是直接用 Konva 画，分三层：
//   world   区块、小区的底色和格子（布局、颜色变了才重画）
//   label   文字、名称牌、标尺、传感器（缩放后按屏幕大小重排）
//   overlay 选中、鼠标所在、对照虚线、拖动时的影子、测距
// 缩放、平移只改这三层的变换；鼠标移动不触发 Vue 重新渲染。图形都不监听事件，点到哪个格子自己按坐标算。
//
//   focus 为空时看整块地；为区块 id 时只看这个区块（"进入区块"）
//   pick  点选的是什么：plot 小区（录入、布置试验）、block 区块（编辑地块）、cell 区块里的格子（编辑区块里的小区）
//   editable 编辑地块：拖分界线改大小、拖特殊区块、拖区块交换位置、拖格子挪小区
//   tool  select 选择、pan 拖动画面、measure 测距
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Konva from 'konva'

import { saveFile } from '@/shared/api/http'
import guardTileUrl from '@/assets/art/guard-tile.webp'
import roadTileUrl from '@/assets/art/road-tile.webp'
import { eastNorth, eastNorthText } from '@/shared/compass'
import {
  CELL_KIND, anchors, blockRect, blockSlot, blockSpan, canMoveCells, fieldSize, fmt, innerCells, innerSizes, keyOf,
  parseKey, round, sum, trackRect, type Axis, type CellKind, type FieldLayout, type LayoutBlock, type Rect, type Span,
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

export type CanvasTool = 'select' | 'pan' | 'measure'

/** 拖动分界线改大小：a 是第 index 行（列）的新大小，b 是下一行（列）的；拖最外边时 b 为 null */
export interface TrackResize {
  scope: 'field' | 'inner'
  axis: Axis
  index: number
  a: number
  b: number | null
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
  /** 画面转多少度（图纸朝上为 0，正北朝上时按真北方向转） */
  rotation?: number
  /** 显示行高列宽标尺（编辑地块时） */
  rulers?: boolean
  /** 编辑地块：可以拖动改大小、挪位置 */
  editable?: boolean
  tool?: CanvasTool
  /** 浮在画布上的工具条占的地方（像素），适应窗口时让开 */
  insets?: { top: number; right: number; bottom: number; left: number }
}>(), {
  focus: null, pick: 'plot', selectedPlots: () => [], selectedBlocks: () => [], selectedCells: () => [],
  links: () => [], devices: () => [], rotation: 0, rulers: false, editable: false, tool: 'select',
  insets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
})

const emit = defineEmits<{
  'update:selectedPlots': [ids: number[]]
  'update:selectedBlocks': [ids: string[]]
  'update:selectedCells': [keys: string[]]
  open: [plotId: number]
  enter: [blockId: string]
  contextmenu: [target: { plotId?: number; blockId?: string; cell?: string }, x: number, y: number]
  /** 拖动特殊区块松手：x、y 是在槽位里的新偏移 */
  moveFit: [blockId: string, x: number, y: number]
  /** 点了标尺上的第 index 行（列） */
  track: [axis: Axis, index: number, additive: boolean]
  /** 拖分界线松手 */
  resizeTrack: [r: TrackResize]
  /** 拖选中的格子松手：整体挪 dr 行 dc 列 */
  moveCells: [blockId: string, keys: string[], dr: number, dc: number]
  /** 把选中的区块拖到另一个区块上松手：交换位置 */
  swapBlocks: [a: string, b: string]
  zoom: [percent: number]
}>()

const FONT = "'Microsoft YaHei UI', 'Microsoft YaHei', 'PingFang SC', 'Noto Sans CJK SC', sans-serif"
const INK = '#1f2a22'
const FOREST = '#1c4231'
const WHEAT = '#d9a748'
const ROAD = '#d9cfb9'
const ROAD_LINE = '#f6f0e2'
const RULER = 30
const SEL_FILL = 'rgba(226, 182, 90, 0.30)'

// ---------- 画布 ----------

const wrap = ref<HTMLDivElement | null>(null)
const host = ref<HTMLDivElement | null>(null)
const tipEl = ref<HTMLDivElement | null>(null)
const bandEl = ref<HTMLDivElement | null>(null)
const readoutEl = ref<HTMLDivElement | null>(null)
const scaleBarEl = ref<HTMLDivElement | null>(null)
const scaleTextEl = ref<HTMLSpanElement | null>(null)

let stage: Konva.Stage | null = null
let bg: Konva.Layer
let worldLayer: Konva.Layer
let labelLayer: Konva.Layer
let overlayLayer: Konva.Layer
let selGroup: Konva.Group
let linkGroup: Konva.Group
let dragGroup: Konva.Group
let measureGroup: Konva.Group
let hoverRect: Konva.Rect
const size = { w: 800, h: 600 }

/** 屏幕 = pan + 旋转(scale × (p − origin))；不放进 Vue 的响应式里，缩放时不触发重新渲染 */
const view = { scale: 20, panX: 400, panY: 300, ox: 0, oy: 0 }
let fitScale = 20
/** 用户自己缩放、拖动过：窗口变大小时不再自动适应 */
let touched = false
let lastZoomPct = 100

const px = (v: number) => v / view.scale
const rad = () => (props.rotation * Math.PI) / 180

function toScreen(p: { x: number; y: number }) {
  const dx = (p.x - view.ox) * view.scale
  const dy = (p.y - view.oy) * view.scale
  const c = Math.cos(rad())
  const s = Math.sin(rad())
  return { x: view.panX + dx * c - dy * s, y: view.panY + dx * s + dy * c }
}

function toContent(p: { x: number; y: number }) {
  const dx = p.x - view.panX
  const dy = p.y - view.panY
  const c = Math.cos(-rad())
  const s = Math.sin(-rad())
  return { x: view.ox + (dx * c - dy * s) / view.scale, y: view.oy + (dx * s + dy * c) / view.scale }
}

const focusBlock = () => (props.focus ? props.layout.blocks.find((b) => b.id === props.focus) ?? null : null)

/** 现在看的范围（单位同布局） */
function contentRect(): Rect {
  const fb = focusBlock()
  if (fb) return blockSlot(props.layout, fb)
  const s = fieldSize(props.layout)
  return { x: 0, y: 0, w: s.w, h: s.h }
}

function applyTransform() {
  if (!stage) return
  const attrs = {
    x: view.panX, y: view.panY, scaleX: view.scale, scaleY: view.scale, rotation: props.rotation,
    offsetX: view.ox, offsetY: view.oy,
  }
  worldLayer.setAttrs(attrs)
  labelLayer.setAttrs(attrs)
  overlayLayer.setAttrs(attrs)
  const pct = Math.round((view.scale / fitScale) * 100)
  if (pct !== lastZoomPct) {
    lastZoomPct = pct
    emit('zoom', pct)
  }
  updateScaleBar()
  scheduleLabels()
  stage.batchDraw()
}

/** 正好放下 rect（旋转后的外框），四周留出标尺和浮动工具条的地方 */
function viewFor(r: Rect, pad: number) {
  const ins = props.insets
  const c = Math.abs(Math.cos(rad()))
  const s = Math.abs(Math.sin(rad()))
  const bw = r.w * c + r.h * s
  const bh = r.w * s + r.h * c
  const availW = Math.max(80, size.w - ins.left - ins.right - pad * 2)
  const availH = Math.max(80, size.h - ins.top - ins.bottom - pad * 2)
  const k = Math.max(0.0001, Math.min(availW / Math.max(bw, 1e-6), availH / Math.max(bh, 1e-6)))
  return {
    scale: k, ox: r.x + r.w / 2, oy: r.y + r.h / 2,
    panX: ins.left + pad + availW / 2, panY: ins.top + pad + availH / 2,
  }
}

let anim = 0

function setView(v: { scale: number; panX: number; panY: number; ox: number; oy: number }, animate: boolean) {
  cancelAnimationFrame(anim)
  if (!animate) {
    Object.assign(view, v)
    applyTransform()
    return
  }
  // 平滑过去：在屏幕上看，中心点和缩放一起变
  const from = { ...view }
  const t0 = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - t0) / 240)
    const e = 1 - (1 - t) ** 3
    view.scale = from.scale * (v.scale / from.scale) ** e
    view.ox = from.ox + (v.ox - from.ox) * e
    view.oy = from.oy + (v.oy - from.oy) * e
    view.panX = from.panX + (v.panX - from.panX) * e
    view.panY = from.panY + (v.panY - from.panY) * e
    applyTransform()
    if (t < 1) anim = requestAnimationFrame(step)
  }
  anim = requestAnimationFrame(step)
}

function fit(animate = false) {
  const margin = props.rulers || focusBlock() ? RULER + 18 : 28
  const v = viewFor(contentRect(), margin)
  fitScale = v.scale
  touched = false
  setView(v, animate)
}

/** 放大到一片区域（找到小区时用） */
function zoomToRect(r: Rect) {
  const v = viewFor(r, 60)
  v.scale = Math.min(v.scale, fitScale * 8)
  touched = true
  setView(v, true)
}

function zoomToPlots(ids: number[]) {
  const rects = ids.map((id) => cellByPlot.get(id)?.rect).filter((r): r is Rect => !!r)
  if (rects.length === 0) return
  const x = Math.min(...rects.map((r) => r.x))
  const y = Math.min(...rects.map((r) => r.y))
  const x2 = Math.max(...rects.map((r) => r.x + r.w))
  const y2 = Math.max(...rects.map((r) => r.y + r.h))
  zoomToRect({ x, y, w: x2 - x, h: y2 - y })
}

function zoomAt(factor: number, at?: { x: number; y: number }) {
  const p = at ?? { x: size.w / 2, y: size.h / 2 }
  const before = toContent(p)
  view.scale = Math.min(fitScale * 40, Math.max(fitScale * 0.25, view.scale * factor))
  const after = toScreen(before)
  view.panX += p.x - after.x
  view.panY += p.y - after.y
  touched = true
  applyTransform()
}

function zoomBy(factor: number) {
  zoomAt(factor)
}

// ---------- 要画的东西（普通数组，不响应式） ----------

interface CellItem {
  blockId: string
  key: string
  span: Span
  plotId: number | null
  kind: CellKind
  rect: Rect
}

interface BlockItem {
  block: LayoutBlock
  slot: Rect
  rect: Rect
  span: Span
  cells: CellItem[]
}

interface TextItem {
  node: Konva.Text
  rect: Rect
  lines: string[]
}

let blockItems: BlockItem[] = []
let cellItems: CellItem[] = []
let cellByPlot = new Map<number, CellItem>()
let cellByKey = new Map<string, CellItem>()
let texts: TextItem[] = []
let badges: { node: Konva.Text; rect: Rect }[] = []
let markers: { node: Konva.Circle; rect: Rect }[] = []
let tags: { node: Konva.Label; item: BlockItem }[] = []

function rebuildModel() {
  const fb = focusBlock()
  const list = fb ? [fb] : props.layout.blocks
  blockItems = list.map((b) => {
    const cells = innerCells(props.layout, b).map((c) => ({
      blockId: b.id, key: c.key, span: c.span, plotId: c.plotId, kind: c.kind, rect: c.rect,
    }))
    return { block: b, slot: blockSlot(props.layout, b), rect: blockRect(props.layout, b), span: blockSpan(props.layout, b), cells }
  })
  cellItems = blockItems.flatMap((s) => s.cells)
  cellByPlot = new Map()
  cellByKey = new Map()
  for (const c of cellItems) {
    if (c.plotId != null) cellByPlot.set(c.plotId, c)
    cellByKey.set(`${c.blockId}:${c.key}`, c)
  }
}

const box = (r: Rect) => ({ x: r.x, y: r.y, width: r.w, height: r.h })

/** 小区之间留一点缝（按小区大小算，不随缩放变） */
function inset(r: Rect, k = 0.035): Rect {
  const g = Math.min(r.w, r.h) * k
  return { x: r.x + g, y: r.y + g, w: r.w - g * 2, h: r.h - g * 2 }
}

// 保护区、道路的底纹（生图后做成的无缝小图）。按实际大小铺：一张图大约 2 米
const tiles: { guard?: HTMLImageElement; road?: HTMLImageElement } = {}

function loadTile(url: string, key: 'guard' | 'road') {
  if (typeof Image === 'undefined') return
  const img = new Image()
  img.onload = () => {
    tiles[key] = img
    scheduleBuild()
  }
  img.src = url
}

function patternFill(img: HTMLImageElement | undefined, meters: number, fallback: string) {
  if (!img) return { fill: fallback }
  const k = meters / props.layout.unit.meters / img.width
  return { fillPatternImage: img, fillPatternRepeat: 'repeat', fillPatternScaleX: k, fillPatternScaleY: k }
}

function kindFill(kind: CellKind) {
  if (kind === 'GUARD') return patternFill(tiles.guard, 1.6, '#d6e3c6')
  if (kind === 'ROAD') return patternFill(tiles.road, 2, ROAD)
  if (kind === 'BLANK') return { fill: '#eee6d4' }
  return { fill: '#e7dcc2' }
}

/** 道路中间一条虚线，顺着长的方向 */
function roadLine(r: Rect): number[] {
  const m = Math.min(r.w, r.h) * 0.3
  return r.w >= r.h ? [r.x + m, r.y + r.h / 2, r.x + r.w - m, r.y + r.h / 2]
    : [r.x + r.w / 2, r.y + m, r.x + r.w / 2, r.y + r.h - m]
}

/** 槽位里特殊区块以外的部分（道路），最多四块 */
function slotRoads(s: BlockItem): Rect[] {
  if (!s.block.fit) return []
  const { slot: o, rect: i } = s
  const out: Rect[] = []
  if (i.y > o.y) out.push({ x: o.x, y: o.y, w: o.w, h: i.y - o.y })
  if (i.y + i.h < o.y + o.h) out.push({ x: o.x, y: i.y + i.h, w: o.w, h: o.y + o.h - i.y - i.h })
  if (i.x > o.x) out.push({ x: o.x, y: i.y, w: i.x - o.x, h: i.h })
  if (i.x + i.w < o.x + o.w) out.push({ x: i.x + i.w, y: i.y, w: o.x + o.w - i.x - i.w, h: i.h })
  return out
}

const shape = { listening: false, perfectDrawEnabled: false, shadowForStrokeEnabled: false }

function addText(rect: Rect, lines: string[], color: string) {
  const node = new Konva.Text({
    ...shape, text: '', align: 'center', verticalAlign: 'middle', fill: color, fontFamily: FONT, lineHeight: 1.3,
    wrap: 'none', ellipsis: true, visible: false,
  })
  labelLayer.add(node)
  texts.push({ node, rect, lines })
}

function buildWorld() {
  if (!stage) return
  rebuildModel()
  worldLayer.destroyChildren()
  labelLayer.destroyChildren()
  texts = []
  badges = []
  markers = []
  tags = []
  const W = worldLayer
  const fb = focusBlock()
  if (!fb) {
    const s = fieldSize(props.layout)
    W.add(new Konva.Rect({
      ...shape, x: 0, y: 0, width: s.w, height: s.h, fill: '#cdbf9f', stroke: FOREST, strokeWidth: 2.5,
      strokeScaleEnabled: false, shadowColor: '#3b2a0c', shadowBlur: 18, shadowOpacity: 0.18, shadowOffsetY: 4,
    }))
  }
  for (const s of blockItems) {
    for (const r of slotRoads(s)) {
      W.add(new Konva.Rect({ ...shape, ...box(r), ...kindFill('ROAD') }))
      W.add(new Konva.Line({ ...shape, points: roadLine(r), stroke: ROAD_LINE, strokeWidth: 1.5, dash: [8, 6], strokeScaleEnabled: false }))
    }
    if (s.block.fit) {
      W.add(new Konva.Rect({
        ...shape, ...box(s.slot), stroke: 'rgba(28, 66, 49, 0.35)', strokeWidth: 1, dash: [4, 4], strokeScaleEnabled: false,
      }))
    }
    const plant = s.block.kind === 'PLANT'
    W.add(new Konva.Rect({
      ...shape, ...box(s.rect), ...kindFill(s.block.kind),
      stroke: plant ? 'rgba(28, 66, 49, 0.55)' : 'rgba(90, 70, 30, 0.25)', strokeWidth: plant ? 1.6 : 1, strokeScaleEnabled: false,
    }))
    if (s.block.kind === 'ROAD') {
      W.add(new Konva.Line({ ...shape, points: roadLine(s.rect), stroke: ROAD_LINE, strokeWidth: 1.5, dash: [8, 6], strokeScaleEnabled: false }))
    }
    if (!plant) addText(s.rect, [s.block.name], '#7a6d52')

    for (const c of s.cells) {
      const info = c.plotId != null ? props.cells.get(c.plotId) : undefined
      const special = c.kind !== 'PLANT'
      const r = inset(c.rect)
      W.add(new Konva.Rect({
        ...shape, ...box(r),
        ...(special ? kindFill(c.kind) : { fill: info?.fill ?? (c.plotId == null ? '#fbf7ee' : '#f4eedc') }),
        opacity: !special && info?.muted ? 0.75 : 1,
        stroke: c.plotId == null && !special ? 'rgba(46, 107, 79, 0.55)' : 'rgba(90, 70, 30, 0.18)',
        strokeWidth: 1, dash: c.plotId == null && !special ? [4, 3] : undefined, strokeScaleEnabled: false,
        cornerRadius: Math.min(r.w, r.h) * 0.06,
      }))
      let lines: string[]
      if (special) lines = [...(info?.lines.slice(0, 1) ?? []), CELL_KIND[c.kind]]
      else if (c.plotId == null) lines = ['新小区']
      else lines = info?.lines ?? []
      addText(c.rect, lines, c.plotId == null && !special ? '#2e6b4f' : special ? '#6b5d40' : INK)
      if (info?.badge) {
        const node = new Konva.Text({
          ...shape, text: info.badge, align: 'right', fontStyle: 'bold', fontFamily: FONT,
          fill: info.badge.includes('!') ? '#b0543a' : '#8a6118', visible: false,
        })
        labelLayer.add(node)
        badges.push({ node, rect: c.rect })
      }
      if (info?.marker) {
        const node = new Konva.Circle({ ...shape, fill: '#2e6b4f', stroke: '#ffffff', strokeWidth: 1.2, strokeScaleEnabled: false })
        labelLayer.add(node)
        markers.push({ node, rect: c.rect })
      }
    }
    if (!fb && plant) {
      const tag = new Konva.Label({ ...shape, visible: false })
      tag.add(new Konva.Tag({ ...shape, fill: 'rgba(28, 66, 49, 0.88)', cornerRadius: 5 }))
      tag.add(new Konva.Text({ ...shape, text: s.block.name, fontSize: 11.5, fontStyle: 'bold', padding: 4, fill: '#fbf3df', fontFamily: FONT }))
      labelLayer.add(tag)
      tags.push({ node: tag, item: s })
    }
  }
  rulerGroup = new Konva.Group(shape)
  devGroup = new Konva.Group(shape)
  labelLayer.add(rulerGroup, devGroup)
  drawSelection()
  drawLinks()
  refreshLabels()
  applyTransform()
}

let buildQueued = false

function scheduleBuild() {
  if (buildQueued) return
  buildQueued = true
  requestAnimationFrame(() => {
    buildQueued = false
    buildWorld()
  })
}

// ---------- 文字、名称牌、标尺、传感器：按屏幕大小排 ----------

let rulerGroup: Konva.Group
let devGroup: Konva.Group

/**
 * 文字保持正着：画在格子中心，反着转回来；转了 90° 时宽高对调。
 * 格子又窄又长（小麦小区常见 2 m × 6.67 m）、横着写不下时，顺着长边竖着写
 */
function upright(r: Rect) {
  const quarter = Math.abs(((props.rotation % 180) + 180) % 180 - 90) < 20
  let w = quarter ? r.h : r.w
  let h = quarter ? r.w : r.h
  let rotation = -props.rotation
  if (w * view.scale < 40 && h > w * 1.8) {
    ;[w, h] = [h, w]
    rotation -= 90
  }
  return { x: r.x + r.w / 2, y: r.y + r.h / 2, w, h, rotation }
}

interface Tick {
  axis: Axis
  index: number
  rect: Rect
}

let ticks: Tick[] = []

function refreshLabels() {
  if (!stage) return
  const sc = view.scale
  for (const t of texts) {
    if (t.lines.length === 0) {
      t.node.visible(false)
      continue
    }
    const b = upright(t.rect)
    const wpx = b.w * sc
    const hpx = b.h * sc
    const n = wpx < 30 || hpx < 16 ? 0 : Math.max(1, Math.min(4, Math.floor((hpx - 4) / 16)))
    if (n === 0) {
      t.node.visible(false)
      continue
    }
    const w = b.w - px(6)
    const h = b.h - px(4)
    t.node.setAttrs({
      visible: true, x: b.x, y: b.y, width: w, height: h, offsetX: w / 2, offsetY: h / 2, rotation: b.rotation,
      fontSize: px(12), text: t.lines.slice(0, n).join('\n'),
    })
  }
  for (const b of badges) {
    const show = b.rect.w * sc > 34 && b.rect.h * sc > 22
    b.node.setAttrs({
      visible: show, x: b.rect.x + b.rect.w - px(4), y: b.rect.y + px(3), width: px(40), offsetX: px(40), fontSize: px(10),
      rotation: -props.rotation,
    })
  }
  for (const m of markers) {
    m.node.setAttrs({
      x: m.rect.x + Math.min(px(9), m.rect.w / 4), y: m.rect.y + m.rect.h - Math.min(px(9), m.rect.h / 4),
      radius: Math.min(px(3.5), m.rect.w / 6),
    })
  }
  for (const t of tags) {
    const r = t.item.rect
    t.node.setAttrs({
      visible: r.w * sc > 46 && r.h * sc > 30, x: r.x + px(4), y: r.y + px(4), scaleX: px(1), scaleY: px(1), rotation: -props.rotation,
    })
  }
  drawRulers()
  drawDevices()
  labelLayer.batchDraw()
  // 选中框、对照虚线的粗细跟屏幕走，不用重画
}

let labelTimer = 0

/** 缩放过程中文字先跟着缩放，停下来再按屏幕大小排（小区多时每一帧都排太慢） */
function scheduleLabels() {
  clearTimeout(labelTimer)
  if (texts.length < 500) {
    labelTimer = window.setTimeout(refreshLabels, 0)
  } else {
    labelTimer = window.setTimeout(refreshLabels, 90)
  }
}

function drawRulers() {
  rulerGroup.destroyChildren()
  ticks = []
  const fb = focusBlock()
  const u = props.layout.unit
  const gap = px(RULER) * 0.8
  const add = (axis: Axis, index: number, rect: Rect, label: string) => {
    ticks.push({ axis, index, rect })
    rulerGroup.add(new Konva.Rect({
      ...shape, ...box(rect), fill: 'rgba(255, 253, 248, 0.94)', stroke: 'rgba(90, 70, 30, 0.25)', strokeWidth: 1,
      strokeScaleEnabled: false, cornerRadius: px(4),
    }))
    const b = upright(rect)
    const room = b.w * view.scale
    const text = room >= label.length * 7 + 8 ? label : room >= 18 ? String(index + 1) : ''
    rulerGroup.add(new Konva.Text({
      ...shape, x: b.x, y: b.y, width: b.w, height: b.h, offsetX: b.w / 2, offsetY: b.h / 2, rotation: b.rotation,
      text, align: 'center', verticalAlign: 'middle', fontSize: px(11), fill: '#6b6450', fontFamily: FONT, wrap: 'none',
    }))
  }
  if (fb) {
    if (!fb.inner) return
    const r = blockRect(props.layout, fb)
    const s = innerSizes(props.layout, fb)
    let x = r.x
    s.cols.forEach((w, i) => {
      add('col', i, { x, y: r.y - gap - px(4), w, h: gap }, props.rulers ? `${i + 1} · ${fmt(w, u)}` : String(i + 1))
      x += w
    })
    let y = r.y
    s.rows.forEach((h, i) => {
      add('row', i, { x: r.x - gap - px(4), y, w: gap, h }, props.rulers ? `${i + 1} · ${fmt(h, u)}` : String(i + 1))
      y += h
    })
    return
  }
  if (!props.rulers) return
  let x = 0
  props.layout.cols.forEach((w, i) => {
    add('col', i, { x, y: -gap - px(4), w, h: gap }, fmt(w, u))
    x += w
  })
  let y = 0
  props.layout.rows.forEach((h, i) => {
    add('row', i, { x: -gap - px(4), y, w: gap, h }, fmt(h, u))
    y += h
  })
}

function drawDevices() {
  devGroup.destroyChildren()
  if (focusBlock()) return
  for (const d of props.devices) {
    devGroup.add(new Konva.Circle({
      ...shape, x: d.x, y: d.y, radius: px(7), fill: d.color, stroke: '#ffffff', strokeWidth: 2, strokeScaleEnabled: false,
    }))
  }
}

// ---------- 选中、对照虚线 ----------

function selBox(r: Rect, strong = true) {
  selGroup.add(new Konva.Rect({
    ...shape, ...box(r), fill: SEL_FILL, stroke: strong ? FOREST : WHEAT, strokeWidth: strong ? 2.5 : 3,
    strokeScaleEnabled: false, cornerRadius: Math.min(r.w, r.h) * 0.06,
  }))
}

function drawSelection() {
  if (!stage) return
  selGroup.destroyChildren()
  if (props.pick === 'plot') {
    for (const id of props.selectedPlots) {
      const c = cellByPlot.get(id)
      if (c) selBox(inset(c.rect))
    }
  } else if (props.pick === 'cell') {
    for (const k of props.selectedCells) {
      const c = cellByKey.get(`${props.focus}:${k}`)
      if (c) selBox(inset(c.rect))
    }
  } else {
    const ids = new Set(props.selectedBlocks)
    for (const s of blockItems) {
      if (!ids.has(s.block.id)) continue
      selBox(s.rect, false)
      if (props.editable && s.block.fit) {
        selGroup.add(new Konva.Rect({ ...shape, ...box(s.rect), stroke: WHEAT, strokeWidth: 2, dash: [6, 4], strokeScaleEnabled: false }))
      }
    }
  }
  overlayLayer.batchDraw()
}

function drawLinks() {
  if (!stage) return
  linkGroup.destroyChildren()
  const rings = new Map<number, boolean>()
  for (const l of props.links) {
    for (const s of l.segments) rings.set(s.to, rings.get(s.to) ?? false)
    for (const id of l.controlPlotIds) rings.set(id, true)
  }
  for (const [id, control] of rings) {
    const c = cellByPlot.get(id)
    if (!c) continue
    const r = inset(c.rect, 0.06)
    linkGroup.add(new Konva.Rect({
      ...shape, ...box(r), stroke: '#c0562f', strokeWidth: control ? 2.5 : 1.5, dash: control ? undefined : [5, 4],
      strokeScaleEnabled: false, cornerRadius: Math.min(r.w, r.h) * 0.06,
    }))
  }
  for (const link of props.links) {
    for (const seg of link.segments) {
      const a = cellByPlot.get(seg.from)?.rect
      const b = cellByPlot.get(seg.to)?.rect
      if (!a || !b) continue
      linkGroup.add(new Konva.Line({
        ...shape, points: [a.x + a.w / 2, a.y + a.h / 2, b.x + b.w / 2, b.y + b.h / 2], stroke: '#c0562f', strokeWidth: 2,
        dash: [8, 6], lineCap: 'round', strokeScaleEnabled: false,
      }))
    }
  }
  overlayLayer.batchDraw()
}

// ---------- 点到了什么 ----------

type Target =
  | { kind: 'tick'; axis: Axis; index: number }
  | { kind: 'tag'; blockId: string }
  | { kind: 'cell'; cell: CellItem }
  | { kind: 'block'; item: BlockItem }
  | { kind: 'none' }

const inRect = (r: Rect, p: { x: number; y: number }) => p.x >= r.x && p.x <= r.x + r.w && p.y >= r.y && p.y <= r.y + r.h

function targetAt(sp: { x: number; y: number }): Target {
  const p = toContent(sp)
  for (const t of ticks) if (inRect(t.rect, p)) return { kind: 'tick', axis: t.axis, index: t.index }
  for (const t of tags) {
    if (!t.node.visible()) continue
    const r = t.node.getClientRect()
    if (sp.x >= r.x && sp.x <= r.x + r.width && sp.y >= r.y && sp.y <= r.y + r.height) return { kind: 'tag', blockId: t.item.block.id }
  }
  for (const c of cellItems) if (inRect(c.rect, p)) return { kind: 'cell', cell: c }
  for (const s of blockItems) if (inRect(s.rect, p)) return { kind: 'block', item: s }
  return { kind: 'none' }
}

function blockIdOf(t: Target): string | null {
  if (t.kind === 'tag') return t.blockId
  if (t.kind === 'cell') return t.cell.blockId
  if (t.kind === 'block') return t.item.block.id
  return null
}

/** 编辑时鼠标是不是在分界线上：整块地上是区块之间（和最外边），区块里是小区之间 */
interface Divider {
  scope: 'field' | 'inner'
  axis: Axis
  index: number
  /** 拖的是最外边：只改这一行（列） */
  outer: boolean
  /** 分界线在布局里的位置、能拖的范围 */
  pos: number
  min: number
  max: number | null
  a: number
  b: number | null
  /** 分界线另一个方向的范围，画线用 */
  from: number
  to: number
}

function dividerAt(sp: { x: number; y: number }): Divider | null {
  if (!props.editable || props.tool !== 'select') return null
  const p = toContent(sp)
  const tol = 6 / view.scale
  const fb = focusBlock()
  const minSize = Math.max(0.01, 4 / view.scale)
  if (fb) {
    if (!fb.inner) return null
    const r = blockRect(props.layout, fb)
    const s = innerSizes(props.layout, fb)
    for (const axis of ['col', 'row'] as Axis[]) {
      const sizes = axis === 'col' ? s.cols : s.rows
      const start = axis === 'col' ? r.x : r.y
      const along = axis === 'col' ? p.y : p.x
      const [lo, hi] = axis === 'col' ? [r.y, r.y + r.h] : [r.x, r.x + r.w]
      if (along < lo - tol || along > hi + tol) continue
      let acc = start
      for (let i = 0; i < sizes.length - 1; i++) {
        acc += sizes[i]!
        if (Math.abs((axis === 'col' ? p.x : p.y) - acc) <= tol) {
          return {
            scope: 'inner', axis, index: i, outer: false, pos: acc, a: sizes[i]!, b: sizes[i + 1]!,
            min: acc - sizes[i]! + minSize, max: acc + sizes[i + 1]! - minSize, from: lo, to: hi,
          }
        }
      }
    }
    return null
  }
  const s = fieldSize(props.layout)
  for (const axis of ['col', 'row'] as Axis[]) {
    const sizes = axis === 'col' ? props.layout.cols : props.layout.rows
    const along = axis === 'col' ? p.y : p.x
    const hi = axis === 'col' ? s.h : s.w
    if (along < -tol || along > hi + tol) continue
    let acc = 0
    for (let i = 0; i < sizes.length; i++) {
      acc += sizes[i]!
      if (Math.abs((axis === 'col' ? p.x : p.y) - acc) <= tol) {
        const outer = i === sizes.length - 1
        return {
          scope: 'field', axis, index: i, outer, pos: acc, a: sizes[i]!, b: outer ? null : sizes[i + 1]!,
          min: acc - sizes[i]! + minSize, max: outer ? null : acc + sizes[i + 1]! - minSize, from: 0, to: hi,
        }
      }
    }
  }
  return null
}

// ---------- 鼠标 ----------

type Drag =
  | { kind: 'pan'; x: number; y: number; px: number; py: number }
  | { kind: 'select'; x: number; y: number; target: Target; additive: boolean; band: boolean }
  | { kind: 'resize'; d: Divider; start: number; value: number }
  | { kind: 'fit'; id: string; fx: number; fy: number; cx: number; cy: number; x: number; y: number; started: boolean; sx: number; sy: number }
  | { kind: 'swap'; id: string; sx: number; sy: number; started: boolean; over: string | null }
  | { kind: 'cells'; blockId: string; keys: string[]; r: number; c: number; dr: number; dc: number; started: boolean; sx: number; sy: number }

let drag: Drag | null = null
const space = { on: false }

function local(e: MouseEvent) {
  const r = host.value!.getBoundingClientRect()
  return { x: e.clientX - r.left, y: e.clientY - r.top }
}

function onKeyDown(e: KeyboardEvent) {
  if (e.code === 'Space' && !isTyping(e)) {
    space.on = true
    if (wrap.value?.matches(':hover')) {
      e.preventDefault()
      setCursor()
    }
  }
  if (e.key === 'Escape') {
    if (drag && drag.kind !== 'select') cancelDrag()
    if (measure.a) clearMeasure()
  }
}

function onKeyUp(e: KeyboardEvent) {
  if (e.code === 'Space') {
    space.on = false
    setCursor()
  }
}

function isTyping(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)
}

/** 区块里第几行第几列（按实际大小），超出时贴边 */
function innerIndexAt(b: LayoutBlock, p: { x: number; y: number }): { r: number; c: number } {
  const r = blockRect(props.layout, b)
  const s = innerSizes(props.layout, b)
  const find = (sizes: number[], v: number) => {
    let acc = 0
    for (let i = 0; i < sizes.length; i++) {
      acc += sizes[i]!
      if (v < acc) return i
    }
    return sizes.length - 1
  }
  return { r: Math.max(0, find(s.rows, p.y - r.y)), c: Math.max(0, find(s.cols, p.x - r.x)) }
}

function onPointerDown(e: PointerEvent) {
  if (!stage) return
  const sp = local(e)
  hideTip()
  hoverKey = ''
  hoverRect.visible(false)
  if (e.button === 1 || (e.button === 0 && (space.on || props.tool === 'pan'))) {
    e.preventDefault()
    drag = { kind: 'pan', x: sp.x, y: sp.y, px: view.panX, py: view.panY }
    host.value!.setPointerCapture(e.pointerId)
    setCursor('grabbing')
    return
  }
  if (e.button !== 0) return
  host.value!.setPointerCapture(e.pointerId)
  if (props.tool === 'measure') {
    measureClick(toContent(sp))
    return
  }
  const div = dividerAt(sp)
  if (div) {
    drag = { kind: 'resize', d: div, start: div.pos, value: div.pos }
    return
  }
  const target = targetAt(sp)
  const additive = e.ctrlKey || e.metaKey || e.shiftKey
  if (props.editable && !additive && props.pick === 'block' && !focusBlock()) {
    const id = target.kind === 'tick' ? null : blockIdOf(target)
    const b = id ? props.layout.blocks.find((x) => x.id === id) : null
    if (b && props.selectedBlocks.includes(b.id)) {
      if (b.fit) {
        const c = toContent(sp)
        drag = { kind: 'fit', id: b.id, fx: b.fit.x, fy: b.fit.y, cx: c.x, cy: c.y, x: b.fit.x, y: b.fit.y, started: false, sx: sp.x, sy: sp.y }
        return
      }
      if (props.selectedBlocks.length === 1) {
        drag = { kind: 'swap', id: b.id, sx: sp.x, sy: sp.y, started: false, over: null }
        return
      }
    }
  }
  if (props.editable && !additive && props.pick === 'cell' && target.kind === 'cell' && props.selectedCells.includes(target.cell.key)) {
    const fb = focusBlock()
    if (fb) {
      const at = innerIndexAt(fb, toContent(sp))
      drag = {
        kind: 'cells', blockId: fb.id, keys: [...props.selectedCells], r: at.r, c: at.c, dr: 0, dc: 0, started: false, sx: sp.x, sy: sp.y,
      }
      return
    }
  }
  drag = { kind: 'select', x: sp.x, y: sp.y, target, additive, band: false }
}

const moved = (d: { sx: number; sy: number }, sp: { x: number; y: number }) => Math.hypot(sp.x - d.sx, sp.y - d.sy) > 4

function onPointerMove(e: PointerEvent) {
  if (!stage) return
  const sp = local(e)
  updateReadout(sp)
  if (!drag) {
    if (props.tool === 'measure') {
      measureMove(toContent(sp))
      return
    }
    hoverAt(sp)
    return
  }
  const d = drag
  if (d.kind === 'pan') {
    view.panX = d.px + sp.x - d.x
    view.panY = d.py + sp.y - d.y
    touched = true
    applyTransform()
    return
  }
  if (d.kind === 'select') {
    if (!d.band && Math.hypot(sp.x - d.x, sp.y - d.y) > 5) d.band = true
    if (d.band) showBand(d.x, d.y, sp.x, sp.y)
    return
  }
  const p = toContent(sp)
  if (d.kind === 'resize') {
    const raw = d.d.axis === 'col' ? p.x : p.y
    const step = niceStep(8 / view.scale)
    let v = Math.round(raw / step) * step
    v = Math.max(d.d.min, d.d.max == null ? v : Math.min(d.d.max, v))
    d.value = v
    drawResizeGhost(d)
    return
  }
  if (d.kind === 'fit') {
    if (!d.started && !moved(d, sp)) return
    d.started = true
    const b = props.layout.blocks.find((x) => x.id === d.id)
    if (!b?.fit) return
    const slot = blockSlot(props.layout, b)
    const step = niceStep(6 / view.scale)
    d.x = round(Math.min(Math.max(Math.round((d.fx + p.x - d.cx) / step) * step, 0), slot.w - b.fit.w), 4)
    d.y = round(Math.min(Math.max(Math.round((d.fy + p.y - d.cy) / step) * step, 0), slot.h - b.fit.h), 4)
    drawFitGhost(b, d.x, d.y)
    return
  }
  if (d.kind === 'swap') {
    if (!d.started && !moved(d, sp)) return
    d.started = true
    const t = targetAt(sp)
    const id = t.kind === 'tick' ? null : blockIdOf(t)
    d.over = id && id !== d.id ? id : null
    drawSwapGhost(d.id, d.over, p)
    return
  }
  if (d.kind === 'cells') {
    if (!d.started && !moved(d, sp)) return
    d.started = true
    const fb = focusBlock()
    if (!fb) return
    const at = innerIndexAt(fb, p)
    d.dr = at.r - d.r
    d.dc = at.c - d.c
    drawCellsGhost(fb, d)
  }
}

function onPointerUp(e: PointerEvent) {
  const d = drag
  drag = null
  if (host.value?.hasPointerCapture(e.pointerId)) host.value.releasePointerCapture(e.pointerId)
  clearDragGhost()
  hideBand()
  setCursor()
  if (!d) return
  if (d.kind === 'select') {
    const additive = d.additive || e.ctrlKey || e.metaKey || e.shiftKey
    if (d.band) {
      const sp = local(e)
      boxSelect({ x1: d.x, y1: d.y, x2: sp.x, y2: sp.y }, additive)
    } else {
      click(d.target, additive)
    }
    return
  }
  if (d.kind === 'resize') {
    const delta = d.value - d.start
    if (Math.abs(delta) < 1e-6) return
    const a = round(d.d.a + delta, 4)
    const b = d.d.b == null ? null : round(d.d.b - delta, 4)
    emit('resizeTrack', { scope: d.d.scope, axis: d.d.axis, index: d.d.index, a, b })
    return
  }
  if (d.kind === 'fit') {
    if (d.started && (d.x !== d.fx || d.y !== d.fy)) emit('moveFit', d.id, d.x, d.y)
    else click(targetAt(local(e)), false)
    return
  }
  if (d.kind === 'swap') {
    if (d.started && d.over && sameSpan(d.id, d.over)) emit('swapBlocks', d.id, d.over)
    else if (!d.started) click(targetAt(local(e)), false)
    return
  }
  if (d.kind === 'cells') {
    if (d.started && (d.dr || d.dc) && canMoveCells(props.layout, d.blockId, d.keys, d.dr, d.dc)) {
      emit('moveCells', d.blockId, d.keys, d.dr, d.dc)
    } else if (!d.started) {
      click(targetAt(local(e)), false)
    }
  }
}

function cancelDrag() {
  drag = null
  clearDragGhost()
  hideBand()
  setCursor()
}

function sameSpan(a: string, b: string): boolean {
  const A = props.layout.blocks.find((x) => x.id === a)
  const B = props.layout.blocks.find((x) => x.id === b)
  if (!A || !B) return false
  const sa = blockSpan(props.layout, A)
  const sb = blockSpan(props.layout, B)
  return sa.rs === sb.rs && sa.cs === sb.cs
}

/** 拖动时按屏幕上大约多少像素取整：1、2、5 × 10 的几次方 */
function niceStep(v: number): number {
  const p = 10 ** Math.floor(Math.log10(Math.max(v, 1e-6)))
  const m = v / p
  return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p
}

function toggle<T>(list: T[], v: T, additive: boolean): T[] {
  if (!additive) return [v]
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v]
}

function blockPlots(id: string): number[] {
  const b = props.layout.blocks.find((x) => x.id === id)
  return Object.values(b?.inner?.plots ?? {})
}

function click(t: Target, additive: boolean) {
  if (t.kind === 'tick') {
    emit('track', t.axis, t.index, additive)
    if (props.pick === 'plot' && focusBlock()) selectTrackPlots(t.axis, t.index, additive)
    return
  }
  if (props.pick === 'plot') {
    if (t.kind === 'cell' && t.cell.plotId != null) {
      emit('update:selectedPlots', toggle(props.selectedPlots, t.cell.plotId, additive))
    } else if (t.kind === 'tag' || (t.kind === 'block' && !focusBlock())) {
      const ids = blockPlots(t.kind === 'tag' ? t.blockId : t.item.block.id)
      emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
    } else if (!additive) {
      emit('update:selectedPlots', [])
    }
    return
  }
  if (props.pick === 'block') {
    const id = blockIdOf(t)
    if (id) emit('update:selectedBlocks', toggle(props.selectedBlocks, id, additive))
    else if (!additive) emit('update:selectedBlocks', [])
    return
  }
  if (t.kind === 'cell') emit('update:selectedCells', toggle(props.selectedCells, t.cell.key, additive))
  else if (!additive) emit('update:selectedCells', [])
}

function selectTrackPlots(axis: Axis, index: number, additive: boolean) {
  const b = focusBlock()
  if (!b?.inner) return
  const ids = anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges)
    .filter((s) => (axis === 'row' ? index >= s.r && index < s.r + s.rs : index >= s.c && index < s.c + s.cs))
    .map((s) => b.inner!.plots[keyOf(s.r, s.c)])
    .filter((v): v is number => v != null)
  emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
}

function boxSelect(b: { x1: number; y1: number; x2: number; y2: number }, additive: boolean) {
  const [l, r] = [Math.min(b.x1, b.x2), Math.max(b.x1, b.x2)]
  const [t, btm] = [Math.min(b.y1, b.y2), Math.max(b.y1, b.y2)]
  const hit = (rect: Rect) => {
    const c = toScreen({ x: rect.x + rect.w / 2, y: rect.y + rect.h / 2 })
    return c.x >= l && c.x <= r && c.y >= t && c.y <= btm
  }
  if (props.pick === 'plot') {
    const ids = cellItems.filter((c) => c.plotId != null && hit(c.rect)).map((c) => c.plotId!)
    emit('update:selectedPlots', additive ? [...new Set([...props.selectedPlots, ...ids])] : ids)
  } else if (props.pick === 'block') {
    const ids = blockItems.filter((s) => hit(s.rect)).map((s) => s.block.id)
    emit('update:selectedBlocks', additive ? [...new Set([...props.selectedBlocks, ...ids])] : ids)
  } else {
    const keys = cellItems.filter((c) => hit(c.rect)).map((c) => c.key)
    emit('update:selectedCells', additive ? [...new Set([...props.selectedCells, ...keys])] : keys)
  }
}

function onDblClick(e: MouseEvent) {
  if (props.tool !== 'select' || space.on) return
  const t = targetAt(local(e))
  if (t.kind === 'tag') {
    emit('enter', t.blockId)
    return
  }
  if (props.pick === 'plot' && t.kind === 'cell' && t.cell.plotId != null) {
    emit('open', t.cell.plotId)
    return
  }
  if (props.pick !== 'plot' && !focusBlock()) {
    const id = blockIdOf(t)
    if (id) emit('enter', id)
  }
}

function onContextMenu(e: MouseEvent) {
  e.preventDefault()
  const t = targetAt(local(e))
  if (t.kind === 'cell' && t.cell.plotId != null && props.pick === 'plot') {
    const id = t.cell.plotId
    if (!props.selectedPlots.includes(id)) emit('update:selectedPlots', [id])
    emit('contextmenu', { plotId: id, blockId: t.cell.blockId }, e.clientX, e.clientY)
    return
  }
  if (props.pick === 'cell' && t.kind === 'cell') {
    if (!props.selectedCells.includes(t.cell.key)) emit('update:selectedCells', [t.cell.key])
    emit('contextmenu', { blockId: t.cell.blockId, cell: t.cell.key, plotId: t.cell.plotId ?? undefined }, e.clientX, e.clientY)
    return
  }
  const id = blockIdOf(t)
  if (id) {
    if (props.pick === 'block' && !props.selectedBlocks.includes(id)) emit('update:selectedBlocks', [id])
    emit('contextmenu', { blockId: id }, e.clientX, e.clientY)
  }
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  hideTip()
  // 触控板双指缩放会带 ctrlKey；普通滚轮每格 100 左右
  const k = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0018))
  zoomAt(Math.min(1.6, Math.max(0.6, k)), local(e))
}

// ---------- 拖动时的影子 ----------

function clearDragGhost() {
  if (!stage) return
  dragGroup.destroyChildren()
  overlayLayer.batchDraw()
}

function drawResizeGhost(d: Extract<Drag, { kind: 'resize' }>) {
  dragGroup.destroyChildren()
  const v = d.value
  const pts = d.d.axis === 'col' ? [v, d.d.from, v, d.d.to] : [d.d.from, v, d.d.to, v]
  dragGroup.add(new Konva.Line({ ...shape, points: pts, stroke: '#c0562f', strokeWidth: 2.5, strokeScaleEnabled: false }))
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const delta = v - d.start
  const a = d.d.a + delta
  const name = d.d.axis === 'col' ? '列' : '行'
  const n = d.d.index + 1
  const text = d.d.b == null
    ? `第 ${n} ${name}：${fmt(a, u)}`
    : `第 ${n} ${name}：${fmt(a, u)}　第 ${n + 1} ${name}：${fmt(d.d.b - delta, u)}`
  const s = toScreen(d.d.axis === 'col' ? { x: v, y: d.d.from } : { x: d.d.from, y: v })
  showTip(text, s.x + 12, s.y + 12)
}

function drawFitGhost(b: LayoutBlock, x: number, y: number) {
  dragGroup.destroyChildren()
  const slot = blockSlot(props.layout, b)
  const r = { x: slot.x + x, y: slot.y + y, w: b.fit!.w, h: b.fit!.h }
  dragGroup.add(new Konva.Rect({
    ...shape, ...box(r), fill: 'rgba(226, 182, 90, 0.35)', stroke: WHEAT, strokeWidth: 2.5, dash: [6, 4], strokeScaleEnabled: false,
  }))
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const s = toScreen({ x: r.x, y: r.y + r.h })
  showTip(`离左边 ${fmt(x, u)} · 离上边 ${fmt(y, u)}`, s.x, s.y + 8)
}

function drawSwapGhost(id: string, over: string | null, p: { x: number; y: number }) {
  dragGroup.destroyChildren()
  const src = blockItems.find((s) => s.block.id === id)
  if (src) {
    dragGroup.add(new Konva.Rect({
      ...shape, x: p.x - src.slot.w / 2, y: p.y - src.slot.h / 2, width: src.slot.w, height: src.slot.h,
      fill: 'rgba(28, 66, 49, 0.18)', stroke: FOREST, strokeWidth: 2, dash: [6, 4], strokeScaleEnabled: false,
    }))
  }
  const dst = over ? blockItems.find((s) => s.block.id === over) : null
  if (dst) {
    const ok = sameSpan(id, dst.block.id)
    dragGroup.add(new Konva.Rect({
      ...shape, ...box(dst.slot), fill: ok ? 'rgba(46, 107, 79, 0.25)' : 'rgba(176, 84, 58, 0.22)',
      stroke: ok ? '#2e6b4f' : '#b0543a', strokeWidth: 3, strokeScaleEnabled: false,
    }))
    const s = toScreen({ x: dst.slot.x, y: dst.slot.y })
    showTip(ok ? `松手和“${dst.block.name}”交换位置` : '大小不一样（占的行列数不同），不能交换', s.x + 8, s.y + 8)
  } else {
    hideTip()
  }
  overlayLayer.batchDraw()
}

function drawCellsGhost(fb: LayoutBlock, d: Extract<Drag, { kind: 'cells' }>) {
  dragGroup.destroyChildren()
  const ok = canMoveCells(props.layout, fb.id, d.keys, d.dr, d.dc)
  const cells = new Map(innerCells(props.layout, fb).map((c) => [c.key, c]))
  const s = innerSizes(props.layout, fb)
  const r0 = blockRect(props.layout, fb)
  const offset = (axis: Axis, from: number, n: number) => {
    const sizes = axis === 'row' ? s.rows : s.cols
    if (n === 0) return 0
    return n > 0 ? sum(sizes, from, from + n) : -sum(sizes, from + n, from)
  }
  for (const k of d.keys) {
    const c = cells.get(k)
    if (!c) continue
    const [r, col] = parseKey(k)
    const dy = offset('row', r, Math.max(-r, Math.min(d.dr, s.rows.length - 1 - r)))
    const dx = offset('col', col, Math.max(-col, Math.min(d.dc, s.cols.length - 1 - col)))
    const rect = inset({ x: c.rect.x + dx, y: c.rect.y + dy, w: c.rect.w, h: c.rect.h })
    dragGroup.add(new Konva.Rect({
      ...shape, ...box(rect), fill: ok ? 'rgba(46, 107, 79, 0.28)' : 'rgba(176, 84, 58, 0.25)',
      stroke: ok ? '#2e6b4f' : '#b0543a', strokeWidth: 2, dash: [5, 4], strokeScaleEnabled: false,
    }))
  }
  overlayLayer.batchDraw()
  const at = toScreen({ x: r0.x, y: r0.y })
  if (d.dr || d.dc) {
    const parts = [d.dr ? `${d.dr > 0 ? '下' : '上'}移 ${Math.abs(d.dr)} 行` : '', d.dc ? `${d.dc > 0 ? '右' : '左'}移 ${Math.abs(d.dc)} 列` : '']
    showTip(ok ? `${parts.filter(Boolean).join('、')}，松手换位置` : '挪不过去：超出区块或碰到合并的格子', at.x + 8, at.y + 8)
  } else {
    hideTip()
  }
}

// ---------- 测距 ----------

const measure: { a: { x: number; y: number } | null; b: { x: number; y: number } | null } = { a: null, b: null }

function measureClick(p: { x: number; y: number }) {
  if (!measure.a || measure.b) {
    measure.a = p
    measure.b = null
  } else {
    measure.b = p
  }
  drawMeasure(p)
}

function measureMove(p: { x: number; y: number }) {
  if (measure.a && !measure.b) drawMeasure(p)
}

function clearMeasure() {
  measure.a = null
  measure.b = null
  if (!stage) return
  measureGroup.destroyChildren()
  overlayLayer.batchDraw()
  hideTip()
}

function drawMeasure(to: { x: number; y: number }) {
  measureGroup.destroyChildren()
  const a = measure.a
  if (!a) return
  const b = measure.b ?? to
  measureGroup.add(new Konva.Line({
    ...shape, points: [a.x, a.y, b.x, b.y], stroke: '#b0543a', strokeWidth: 2.5, strokeScaleEnabled: false, lineCap: 'round',
  }))
  for (const p of [a, b]) {
    measureGroup.add(new Konva.Circle({ ...shape, x: p.x, y: p.y, radius: px(4), fill: '#fff', stroke: '#b0543a', strokeWidth: 2, strokeScaleEnabled: false }))
  }
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const len = Math.hypot(b.x - a.x, b.y - a.y)
  const meters = len * u.meters
  const text = u.symbol === 'm' ? `${round(meters, 2)} m` : `${fmt(len, u)}（${round(meters, 2)} m）`
  const s = toScreen({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 })
  showTip(`距离 ${text}${measure.b ? '　再点一下重新量' : ''}`, s.x + 10, s.y + 10)
}

// ---------- 鼠标所在：外框、说明 ----------

let hoverKey = ''

function hoverAt(sp: { x: number; y: number }) {
  const div = dividerAt(sp)
  if (div) {
    hoverKey = ''
    hoverRect.visible(false)
    overlayLayer.batchDraw()
    setCursor(div.axis === 'col' ? 'col-resize' : 'row-resize')
    showTip(`拖动改${div.axis === 'col' ? '宽' : '高'}${div.outer ? '（最外边，地块跟着变大变小）' : ''}`, sp.x + 14, sp.y + 14)
    return
  }
  const t = targetAt(sp)
  let rect: Rect | null = null
  let text = ''
  let key = ''
  if (t.kind === 'cell') {
    key = `c:${t.cell.blockId}:${t.cell.key}`
    rect = inset(t.cell.rect)
    text = t.cell.plotId != null ? props.cells.get(t.cell.plotId)?.tooltip ?? '' : ''
    if (t.cell.kind !== 'PLANT') text = [text.split('\n')[0] ?? '', CELL_KIND[t.cell.kind]].filter(Boolean).join('\n')
    if (!text && t.cell.plotId == null) text = t.cell.kind === 'PLANT' ? '新小区（保存布局后建好）' : CELL_KIND[t.cell.kind]
  } else if (t.kind === 'block' || t.kind === 'tag') {
    const id = blockIdOf(t)!
    key = `b:${id}`
    rect = blockItems.find((s) => s.block.id === id)?.rect ?? null
    text = blockTip(id)
  } else if (t.kind === 'tick') {
    key = `t:${t.axis}:${t.index}`
    text = `点一下选中第 ${t.index + 1} ${t.axis === 'row' ? '行' : '列'}`
  }
  setCursor(cursorFor(t))
  if (key !== hoverKey) {
    hoverKey = key
    if (rect && props.tool === 'select') {
      hoverRect.setAttrs({ ...box(rect), visible: true, cornerRadius: Math.min(rect.w, rect.h) * 0.06 })
    } else {
      hoverRect.visible(false)
    }
    overlayLayer.batchDraw()
  }
  if (text) showTip(text, sp.x > size.w - 250 ? sp.x - 240 : sp.x + 16, sp.y > size.h - 130 ? sp.y - 120 : sp.y + 16)
  else hideTip()
}

function cursorFor(t: Target): string {
  if (!props.editable) return t.kind === 'tick' || t.kind === 'tag' ? 'pointer' : 'default'
  const id = t.kind === 'tick' ? null : blockIdOf(t)
  if (props.pick === 'block' && id && props.selectedBlocks.includes(id)) return 'move'
  if (props.pick === 'cell' && t.kind === 'cell' && props.selectedCells.includes(t.cell.key)) return 'move'
  return t.kind === 'tick' ? 'pointer' : 'default'
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
  if (props.editable && props.pick === 'block') {
    lines.push(props.selectedBlocks.includes(b.id) ? (b.fit ? '拖动在原位置里挪动' : '拖到别的区块上交换位置') : '双击进入区块')
  } else if (!focusBlock() && b.kind === 'PLANT') {
    lines.push('双击名称牌进入区块')
  }
  return lines.join('\n')
}

function onLeave() {
  hoverKey = ''
  if (stage) {
    hoverRect.visible(false)
    overlayLayer.batchDraw()
  }
  if (!drag) hideTip()
  if (readoutEl.value) readoutEl.value.textContent = ''
}

function showTip(text: string, x: number, y: number) {
  const el = tipEl.value
  if (!el) return
  if (el.textContent !== text) el.textContent = text
  el.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px)`
  el.style.display = 'block'
}

function hideTip() {
  if (tipEl.value) tipEl.value.style.display = 'none'
}

function showBand(x1: number, y1: number, x2: number, y2: number) {
  const el = bandEl.value
  if (!el) return
  el.style.display = 'block'
  el.style.left = `${Math.min(x1, x2)}px`
  el.style.top = `${Math.min(y1, y2)}px`
  el.style.width = `${Math.abs(x2 - x1)}px`
  el.style.height = `${Math.abs(y2 - y1)}px`
}

function hideBand() {
  if (bandEl.value) bandEl.value.style.display = 'none'
}

function setCursor(c?: string) {
  if (!host.value) return
  let v = c
  if (!v) v = props.tool === 'measure' ? 'crosshair' : props.tool === 'pan' || space.on ? 'grab' : 'default'
  if (space.on && !c) v = 'grab'
  if (host.value.style.cursor !== v) host.value.style.cursor = v
}

/** 左下角：鼠标离地块中心往东往北多远 */
function updateReadout(sp: { x: number; y: number }) {
  const el = readoutEl.value
  if (!el) return
  const p = toContent(sp)
  const s = fieldSize(props.layout)
  el.textContent = eastNorthText(eastNorth(p, { x: s.w / 2, y: s.h / 2 }, props.layout.northDeg ?? 0, props.layout.unit.meters))
}

/** 比例尺：大约 90 像素对应的整数长度 */
function updateScaleBar() {
  const bar = scaleBarEl.value
  const label = scaleTextEl.value
  if (!bar || !label) return
  const u = props.layout.unit
  const len = niceStep(90 / view.scale)
  bar.style.width = `${Math.round(len * view.scale)}px`
  label.textContent = fmt(len, u)
}

// ---------- 导出 ----------

/** 导出 jpg 种植图：先适应窗口，白底，不带选中 */
async function exportJpeg(filename: string) {
  if (!stage) return
  hideTip()
  fit()
  const white = new Konva.Rect({ ...shape, x: 0, y: 0, width: size.w, height: size.h, fill: '#ffffff' })
  bg.add(white)
  selGroup.visible(false)
  hoverRect.visible(false)
  await nextTick()
  try {
    saveFile(stage.toDataURL({ mimeType: 'image/jpeg', quality: 0.92, pixelRatio: 2 }), filename)
  } finally {
    white.destroy()
    selGroup.visible(true)
    stage.batchDraw()
  }
}

/** 一个点（布局里的坐标）在画布上的位置，给外面放方位字用 */
function screenOf(p: { x: number; y: number }) {
  return toScreen(p)
}

defineExpose({ fit, zoomBy, zoomToPlots, zoomToRect, exportJpeg, screenOf, contentRect, clearMeasure })

// 开发时给自动化测试用：布局坐标 → 画布上的位置
if (import.meta.env.DEV) {
  ;(window as unknown as { __fieldCanvas?: unknown }).__fieldCanvas = { screenOf }
}

// ---------- 生命周期 ----------

let observer: ResizeObserver | null = null

onMounted(() => {
  const el = host.value!
  stage = new Konva.Stage({ container: el, width: Math.max(200, el.clientWidth), height: Math.max(200, el.clientHeight), listening: false })
  size.w = stage.width()
  size.h = stage.height()
  bg = new Konva.Layer({ listening: false })
  worldLayer = new Konva.Layer({ listening: false })
  labelLayer = new Konva.Layer({ listening: false })
  overlayLayer = new Konva.Layer({ listening: false })
  selGroup = new Konva.Group(shape)
  linkGroup = new Konva.Group(shape)
  dragGroup = new Konva.Group(shape)
  measureGroup = new Konva.Group(shape)
  hoverRect = new Konva.Rect({ ...shape, visible: false, stroke: 'rgba(28, 66, 49, 0.75)', strokeWidth: 2, strokeScaleEnabled: false, fill: 'rgba(255, 255, 255, 0.18)' })
  overlayLayer.add(linkGroup, hoverRect, selGroup, dragGroup, measureGroup)
  stage.add(bg, worldLayer, labelLayer, overlayLayer)
  loadTile(guardTileUrl, 'guard')
  loadTile(roadTileUrl, 'road')
  buildWorld()
  fit()

  observer = new ResizeObserver(() => {
    if (!stage) return
    const w = Math.max(200, el.clientWidth)
    const h = Math.max(200, el.clientHeight)
    if (w === size.w && h === size.h) return
    size.w = w
    size.h = h
    stage.size({ width: w, height: h })
    if (!touched) fit()
    else stage.batchDraw()
  })
  observer.observe(el)
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(anim)
  clearTimeout(labelTimer)
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  stage?.destroy()
  stage = null
})

watch(() => [props.layout, props.cells, props.focus, props.pick], scheduleBuild)
watch(() => props.rotation, () => {
  buildWorld()
  fit()
})
watch(() => props.focus, () => nextTick(() => fit(true)))
watch(() => [props.selectedPlots, props.selectedBlocks, props.selectedCells, props.editable], drawSelection)
watch(() => props.links, drawLinks)
watch(() => [props.devices, props.rulers], refreshLabels)
watch(() => props.tool, (t) => {
  if (t !== 'measure') clearMeasure()
  setCursor()
})
watch(() => props.insets, () => { if (!touched) fit() }, { deep: true })
// 布局大小变了（改行高列宽、加删行列）时，没手动缩放过就重新放好
let lastSize = ''
watch(() => props.layout, () => {
  const r = contentRect()
  const k = `${round(r.w, 3)}x${round(r.h, 3)}`
  if (k !== lastSize) {
    lastSize = k
    if (!touched) nextTick(fit)
  }
})
</script>

<template>
  <div ref="wrap" class="field-canvas" @contextmenu.prevent>
    <div
      ref="host"
      class="stage-host"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="cancelDrag"
      @pointerleave="onLeave"
      @dblclick="onDblClick"
      @contextmenu="onContextMenu"
      @wheel="onWheel"
    />
    <div ref="bandEl" class="band" />
    <div ref="tipEl" class="tip" />
    <div class="scale">
      <div ref="scaleBarEl" class="scale-bar" />
      <span ref="scaleTextEl" class="num" />
      <span ref="readoutEl" class="readout num" />
    </div>
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

.stage-host {
  position: absolute;
  inset: 0;
  touch-action: none;
}

.band {
  display: none;
  position: absolute;
  z-index: 2;
  background: rgba(46, 107, 79, 0.1);
  border: 1.5px dashed #2e6b4f;
  border-radius: 2px;
  pointer-events: none;
}

.tip {
  display: none;
  position: absolute;
  left: 0;
  top: 0;
  z-index: 3;
  max-width: 280px;
  padding: 8px 11px;
  border-radius: 9px;
  background: rgba(24, 58, 43, 0.95);
  color: #f6efdc;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-line;
  pointer-events: none;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.22);
}

.scale {
  position: absolute;
  left: 14px;
  bottom: 12px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ink-2);
  pointer-events: none;
}

.scale-bar {
  height: 6px;
  border: 1.5px solid var(--ink-2);
  border-top: none;
}

.readout {
  margin-left: 6px;
  color: var(--ink-3);
}
</style>
