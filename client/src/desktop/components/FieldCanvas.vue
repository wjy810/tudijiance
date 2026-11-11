<script setup lang="ts">
// 平面图（俯视）：按实际尺寸画地块里的区块、道路、保护区、空地和区块里的小区块。
//
// 为了快，这里不用 Vue 模板画图形，而是直接用 Konva 画，分三层：
//   world   区块、小区块的底色和格子（布局、颜色变了才重画）
//   label   文字、名称牌、标尺、传感器（缩放后按屏幕大小重排）
//   overlay 选中、鼠标所在、对照的红框、拖动时的影子、测距
// 缩放、平移只改这三层的变换；鼠标移动不触发 Vue 重新渲染。图形都不监听事件，点到哪个格子自己按坐标算。
//
//   focus 为空时看整块地；为区块 id 时只看这个区块（"进入区块"）
//   pick  点选的是什么：plot 小区块（录入、布置试验）、block 区块（编辑地块）、cell 区块里的格子（编辑区块里的小区块）
//   editable 编辑地块：区块、道路都是随便摆的矩形——按住就能拖着挪，拖四周的小方块改大小，拖地块的右边、下边改地块大小；
//            进了区块以后拖分界线改小区块大小、拖格子挪小区块
//   tool  select 选择、pan 拖动画面、measure 测距；编辑地块时还有 road 画道路、block 画区块
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Konva from 'konva'

import { saveFile } from '@/shared/api/http'
import guardTileUrl from '@/assets/art/guard-tile.webp'
import roadTileUrl from '@/assets/art/road-tile.webp'
import { eastNorth, eastNorthText } from '@/shared/compass'
import {
  CELL_KIND, MIN_SIZE, anchors, blockRect, boundsOf, canMoveCells, conflicts, contentBounds, drawOrder, drawRoad, fieldSize, fmt,
  innerCells, innerCountFor, innerSizes, keyOf, overlaps, parseKey, plotSize, round, sum,
  type Axis, type CellKind, type FieldLayout, type LayoutBlock, type Rect, type Span,
} from '@/shared/fieldLayout'
import {
  nearestLine, resizeBy, roadStrip, snapLines, snapMove, snapPoint, type Guide, type Handle, type SnapLine, type SnapLines,
} from '@/shared/layoutSnap'

export interface CanvasCell {
  lines: string[]
  fill: string
  badge?: string
  marker?: boolean
  tooltip?: string
  /** 保护区、走道的小区块画得淡一些 */
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

/** 一组对照：对照小区块描红边（整个区块是对照组时整块描边、挂"对照组"），正在看的一组再把比较的小区块描出来 */
export interface CanvasLink {
  id: number
  label: string
  controlPlotIds: number[]
  comparePlotIds: number[]
  /** 对照小区块正好是这些区块的全部小区块 */
  controlBlockIds: string[]
  /** 正在看的一组 */
  active: boolean
}

export type CanvasTool = 'select' | 'pan' | 'measure' | 'road' | 'block'

/** 进了区块以后拖小区块之间的分界线：a 是第 index 行（列）的新大小，b 是下一行（列）的 */
export interface TrackResize {
  axis: Axis
  index: number
  a: number
  b: number
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
  /** 显示标尺（编辑地块时） */
  rulers?: boolean
  /** 编辑地块：可以拖动改大小、挪位置 */
  editable?: boolean
  tool?: CanvasTool
  /** 画的道路多宽（单位同布局）；摆区块时也按它留出一条路的间隔 */
  roadWidth?: number
  /** 浮在画布上的工具条占的地方（像素），适应窗口时让开 */
  insets?: { top: number; right: number; bottom: number; left: number }
}>(), {
  focus: null, pick: 'plot', selectedPlots: () => [], selectedBlocks: () => [], selectedCells: () => [],
  links: () => [], devices: () => [], rotation: 0, rulers: false, editable: false, tool: 'select', roadWidth: 1,
  insets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
})

const emit = defineEmits<{
  'update:selectedPlots': [ids: number[]]
  'update:selectedBlocks': [ids: string[]]
  'update:selectedCells': [keys: string[]]
  open: [plotId: number]
  enter: [blockId: string]
  contextmenu: [target: { plotId?: number; blockId?: string; cell?: string }, x: number, y: number]
  /** 把选中的区块拖到别处松手：一起挪 dx、dy；copy 为 true 是按着 Alt 松的手，复制一份过去 */
  moveBlocks: [ids: string[], dx: number, dy: number, copy: boolean]
  /** 拖区块四周的小方块松手：新的位置和大小 */
  resizeBlock: [id: string, rect: Rect]
  /** 拖地块的右边、下边松手 */
  resizeField: [w: number, h: number]
  /** 道路工具画了一条：rect 是画出来的长条，axis 是走向（row 横向、col 纵向） */
  drawRoad: [rect: Rect, axis: Axis]
  /** 区块工具拖出了一个方框 */
  drawBlock: [rect: Rect]
  /** 点了区块里标尺上的第 index 行（列） */
  track: [axis: Axis, index: number, additive: boolean]
  /** 进了区块以后拖分界线松手 */
  resizeTrack: [r: TrackResize]
  /** 拖选中的格子松手：整体挪 dr 行 dc 列 */
  moveCells: [blockId: string, keys: string[], dr: number, dc: number]
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
let chromeGroup: Konva.Group
let conflictGroup: Konva.Group
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
  if (fb) return blockRect(props.layout, fb)
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

/** 放大到一片区域（找到小区块时用） */
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
  rect: Rect
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
/** 对照区块右上角的"对照组" */
let ckTags: { node: Konva.Label; rect: Rect }[] = []
const CK = '#c0562f'

function rebuildModel() {
  const fb = focusBlock()
  // 道路垫在下面，区块画在上面
  const list = fb ? [fb] : drawOrder(props.layout)
  blockItems = list.map((b) => {
    const cells = innerCells(props.layout, b).map((c) => ({
      blockId: b.id, key: c.key, span: c.span, plotId: c.plotId, kind: c.kind, rect: c.rect,
    }))
    return { block: b, rect: blockRect(props.layout, b), cells }
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

/** 小区块之间留一点缝（按小区块大小算，不随缩放变） */
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
      else if (c.plotId == null) lines = ['新小区块']
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
  ckTags = []
  drawConflicts()
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
 * 格子又窄又长（小麦小区块常见 2 m × 6.67 m）、横着写不下时，顺着长边竖着写
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
  placeCkTags()
  drawChrome()
  labelLayer.batchDraw()
  // 选中框、对照虚线的粗细跟屏幕走，不用重画
}

let labelTimer = 0

/** 缩放过程中文字先跟着缩放，停下来再按屏幕大小排（小区块多时每一帧都排太慢） */
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
  const add = (axis: Axis, index: number, rect: Rect, label: string, road = false) => {
    ticks.push({ axis, index, rect })
    rulerGroup.add(new Konva.Rect({
      ...shape, ...box(rect), fill: 'rgba(255, 253, 248, 0.94)', stroke: 'rgba(90, 70, 30, 0.25)', strokeWidth: 1,
      strokeScaleEnabled: false, cornerRadius: px(4),
    }))
    const b = upright(rect)
    const room = b.w * view.scale
    // 放不下尺寸时写第几行（列）；道路放不下就不写，免得窄道路上一个个数字看着乱
    const text = room >= label.length * 7 + 8 ? label : room >= 18 && !road ? String(index + 1) : ''
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
  // 整块地：上边、左边各一条刻度尺，从左上角量起
  const size0 = fieldSize(props.layout)
  const step = niceStep(64 / view.scale)
  const off = gap + px(4)
  const strip = (r: Rect) => rulerGroup.add(new Konva.Rect({
    ...shape, ...box(r), fill: 'rgba(255, 253, 248, 0.94)', stroke: 'rgba(90, 70, 30, 0.25)', strokeWidth: 1,
    strokeScaleEnabled: false, cornerRadius: px(4),
  }))
  const mark = (x: number, y: number, text: string) => {
    const w = px(46)
    const h = px(14)
    rulerGroup.add(new Konva.Text({
      ...shape, x, y, width: w, height: h, offsetX: w / 2, offsetY: h / 2, rotation: -props.rotation, text, align: 'center',
      verticalAlign: 'middle', fontSize: px(10.5), fill: '#6b6450', fontFamily: FONT, wrap: 'none',
    }))
  }
  strip({ x: 0, y: -off, w: size0.w, h: gap })
  strip({ x: -off, y: 0, w: gap, h: size0.h })
  const tick = { ...shape, stroke: 'rgba(90, 70, 30, 0.45)', strokeWidth: 1, strokeScaleEnabled: false }
  for (let i = 0; i * step <= size0.w + 1e-9 && i < 400; i++) {
    const v = i * step
    rulerGroup.add(new Konva.Line({ ...tick, points: [v, -px(4), v, -px(4) - gap * 0.28] }))
    // 两头的数字往里收一点，不压到拐角
    if (v > px(16) && v < size0.w - px(16)) mark(v, -off + gap * 0.42, String(round(v, 4)))
  }
  for (let i = 0; i * step <= size0.h + 1e-9 && i < 400; i++) {
    const v = i * step
    rulerGroup.add(new Konva.Line({ ...tick, points: [-px(4), v, -px(4) - gap * 0.28, v] }))
    if (v > px(16) && v < size0.h - px(16)) mark(-off + gap * 0.42, v, String(round(v, 4)))
  }
  mark(-off + gap * 0.5, -off + gap * 0.5, u.symbol)
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
    for (const s of blockItems) if (ids.has(s.block.id)) selBox(s.rect, false)
  }
  drawChrome()
  overlayLayer.batchDraw()
}

/**
 * 编辑地块时选中的东西四周：只选了一个时八个小方块（拖它改大小），标尺上标出选中的范围和大小。
 * 大小跟屏幕走，缩放后重画
 */
function drawChrome() {
  if (!stage) return
  chromeGroup.destroyChildren()
  if (!freeEdit()) return
  const sel = blockItems.filter((s) => props.selectedBlocks.includes(s.block.id))
  const b = boundsOf(sel.map((s) => s.rect))
  if (b && props.rulers) {
    const u = props.layout.unit
    const gap = px(RULER) * 0.8
    const off = gap + px(4)
    // 不透明：盖住下面的刻度数字，免得和大小叠在一起
    const bar = { ...shape, fill: '#f0d596', stroke: '#b98a2c', strokeWidth: 1, strokeScaleEnabled: false, cornerRadius: px(3) }
    chromeGroup.add(new Konva.Rect({ ...bar, x: b.x, y: -off, width: b.w, height: gap }))
    chromeGroup.add(new Konva.Rect({ ...bar, x: -off, y: b.y, width: gap, height: b.h }))
    const label = (x: number, y: number, text: string, room: number, turn: number) => {
      if (room * view.scale < text.length * 7 + 6) return
      const w = px(text.length * 7 + 10)
      const h = px(14)
      chromeGroup.add(new Konva.Text({
        ...shape, x, y, width: w, height: h, offsetX: w / 2, offsetY: h / 2, rotation: turn, text, align: 'center', verticalAlign: 'middle',
        fontSize: px(11), fontStyle: 'bold', fill: '#5b4310', fontFamily: FONT, wrap: 'none',
      }))
    }
    label(b.x + b.w / 2, -off + gap / 2, fmt(b.w, u), b.w, -props.rotation)
    label(-off + gap / 2, b.y + b.h / 2, fmt(b.h, u), b.h, -props.rotation - 90)
  }
  const it = selectedItem()
  if (it) {
    const k = px(8)
    for (const h of handlesOf(it.rect)) {
      chromeGroup.add(new Konva.Rect({
        ...shape, x: h.x - k / 2, y: h.y - k / 2, width: k, height: k, fill: '#ffffff', stroke: FOREST, strokeWidth: 1.6,
        strokeScaleEnabled: false, cornerRadius: k * 0.2,
      }))
    }
  }
}

/** 编辑地块时叠在一起的区块：描红（叠着不能保存） */
function drawConflicts() {
  if (!stage) return
  conflictGroup.destroyChildren()
  if (!props.editable || focusBlock()) return
  const bad = new Set(conflicts(props.layout).flat())
  for (const s of blockItems) {
    if (!bad.has(s.block.id)) continue
    conflictGroup.add(new Konva.Rect({
      ...shape, ...box(s.rect), fill: 'rgba(192, 86, 47, 0.2)', stroke: CK, strokeWidth: 2.5, dash: [7, 4], strokeScaleEnabled: false,
    }))
  }
}

function drawLinks() {
  if (!stage) return
  linkGroup.destroyChildren()
  for (const t of ckTags) t.node.destroy()
  ckTags = []
  const ckBlocks = new Map<string, boolean>()
  const ckPlots = new Map<number, boolean>()
  const compared = new Set<number>()
  for (const l of props.links) {
    for (const id of l.controlBlockIds) ckBlocks.set(id, (ckBlocks.get(id) ?? false) || l.active)
    for (const id of l.controlPlotIds) ckPlots.set(id, (ckPlots.get(id) ?? false) || l.active)
    if (l.active) for (const id of l.comparePlotIds) compared.add(id)
  }
  // 对照区块：整块描边，右上角挂"对照组"
  const inCkBlock = new Set<number>()
  for (const s of blockItems) {
    const active = ckBlocks.get(s.block.id)
    if (active == null) continue
    for (const c of s.cells) if (c.plotId != null) inCkBlock.add(c.plotId)
    linkGroup.add(new Konva.Rect({
      ...shape, ...box(s.rect), stroke: CK, strokeWidth: active ? 4 : 3, fill: active ? 'rgba(192, 86, 47, 0.08)' : undefined,
      strokeScaleEnabled: false,
    }))
    const tag = new Konva.Label({ ...shape, visible: false })
    tag.add(new Konva.Tag({ ...shape, fill: CK, cornerRadius: 5 }))
    tag.add(new Konva.Text({ ...shape, text: '对照组', fontSize: 11.5, fontStyle: 'bold', padding: 4, fill: '#ffffff', fontFamily: FONT }))
    labelLayer.add(tag)
    ckTags.push({ node: tag, rect: s.rect })
  }
  // 单个的对照小区块：红边
  for (const [id, active] of ckPlots) {
    if (inCkBlock.has(id)) continue
    const c = cellByPlot.get(id)
    if (!c) continue
    const r = inset(c.rect, 0.05)
    linkGroup.add(new Konva.Rect({
      ...shape, ...box(r), stroke: CK, strokeWidth: active ? 3 : 2, fill: active ? 'rgba(192, 86, 47, 0.10)' : undefined,
      strokeScaleEnabled: false, cornerRadius: Math.min(r.w, r.h) * 0.06,
    }))
  }
  // 正在看的一组里和对照比的小区块：虚线框
  for (const id of compared) {
    const c = cellByPlot.get(id)
    if (!c) continue
    const r = inset(c.rect, 0.07)
    linkGroup.add(new Konva.Rect({
      ...shape, ...box(r), stroke: '#8a6118', strokeWidth: 1.5, dash: [5, 4], strokeScaleEnabled: false,
      cornerRadius: Math.min(r.w, r.h) * 0.06,
    }))
  }
  placeCkTags()
  overlayLayer.batchDraw()
  labelLayer.batchDraw()
}

function placeCkTags() {
  const sc = view.scale
  for (const t of ckTags) {
    const r = t.rect
    t.node.setAttrs({
      visible: r.w * sc > 70 && r.h * sc > 30, x: r.x + r.w - px(4), y: r.y + px(4), offsetX: t.node.width(),
      scaleX: px(1), scaleY: px(1), rotation: -props.rotation,
    })
  }
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

/** 进了区块以后，鼠标是不是在小区块之间的分界线上 */
interface Divider {
  axis: Axis
  index: number
  /** 分界线在布局里的位置、能拖的范围 */
  pos: number
  min: number
  max: number
  a: number
  b: number
  /** 分界线另一个方向的范围，画线用 */
  from: number
  to: number
}

function dividerAt(sp: { x: number; y: number }): Divider | null {
  if (!props.editable || props.tool !== 'select') return null
  const fb = focusBlock()
  if (!fb?.inner) return null
  const p = toContent(sp)
  const tol = 5 / view.scale
  const minSize = Math.max(0.01, 4 / view.scale)
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
          axis, index: i, pos: acc, a: sizes[i]!, b: sizes[i + 1]!,
          min: acc - sizes[i]! + minSize, max: acc + sizes[i + 1]! - minSize, from: lo, to: hi,
        }
      }
    }
  }
  return null
}

// ---------- 鼠标 ----------

interface Pt {
  x: number
  y: number
}

type Drag =
  | { kind: 'pan'; x: number; y: number; px: number; py: number }
  | { kind: 'select'; x: number; y: number; target: Target; additive: boolean; band: boolean }
  | { kind: 'resize'; d: Divider; start: number; value: number }
  | {
    kind: 'bmove'; ids: string[]; hit: string; from: Pt; rects: Rect[]; box: Rect; lines: SnapLines
    dx: number; dy: number; copy: boolean; started: boolean; sx: number; sy: number
  }
  | { kind: 'bresize'; id: string; handle: Handle; start: Rect; rect: Rect; lines: SnapLines }
  | { kind: 'fresize'; edge: 'e' | 's' | 'se'; w: number; h: number; min: { w: number; h: number } }
  | { kind: 'road' | 'block'; a: Pt; b: Pt; lines: SnapLines; started: boolean; sx: number; sy: number }
  | { kind: 'cells'; blockId: string; keys: string[]; r: number; c: number; dr: number; dc: number; started: boolean; sx: number; sy: number }

let drag: Drag | null = null
const space = { on: false }

/** 在整块地上编辑：区块、道路随便摆 */
const freeEdit = () => props.editable && props.pick === 'block' && !focusBlock()
/** 离线多近算贴上（屏幕上大约 7 像素） */
const snapTol = () => 7 / view.scale
/** 没贴上线时位置取整到多少（屏幕上大约 5 像素：1、2、5 × 10 的几次方） */
const gridStep = () => niceStep(5 / view.scale)
const NO_LINES: SnapLines = { xs: [], ys: [] }

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

// 选中一个区块时四周的八个小方块：拖它改大小
const HANDLES: { h: Handle; fx: number; fy: number }[] = [
  { h: 'nw', fx: 0, fy: 0 }, { h: 'ne', fx: 1, fy: 0 }, { h: 'se', fx: 1, fy: 1 }, { h: 'sw', fx: 0, fy: 1 },
  { h: 'n', fx: 0.5, fy: 0 }, { h: 'e', fx: 1, fy: 0.5 }, { h: 's', fx: 0.5, fy: 1 }, { h: 'w', fx: 0, fy: 0.5 },
]

/** 现在画出来的小方块（区块太小时只留四个角） */
function handlesOf(r: Rect): { h: Handle; x: number; y: number }[] {
  const roomy = (len: number) => len * view.scale >= 34
  return HANDLES
    .filter((k) => k.h.length === 2 || ((k.h === 'n' || k.h === 's') ? roomy(r.w) : roomy(r.h)))
    .map((k) => ({ h: k.h, x: r.x + r.w * k.fx, y: r.y + r.h * k.fy }))
}

function selectedItem(): BlockItem | null {
  if (!freeEdit() || props.tool !== 'select' || props.selectedBlocks.length !== 1) return null
  return blockItems.find((s) => s.block.id === props.selectedBlocks[0]) ?? null
}

function handleAt(sp: Pt): { id: string; handle: Handle } | null {
  const it = selectedItem()
  if (!it) return null
  for (const k of handlesOf(it.rect)) {
    const s = toScreen(k)
    if (Math.abs(s.x - sp.x) <= 7 && Math.abs(s.y - sp.y) <= 7) return { id: it.block.id, handle: k.h }
  }
  return null
}

/** 鼠标在地块右边、下边的外侧：拖动改地块大小 */
function fieldEdgeAt(sp: Pt): 'e' | 's' | 'se' | null {
  if (!freeEdit() || props.tool !== 'select') return null
  const p = toContent(sp)
  const s = fieldSize(props.layout)
  const out = 10 / view.scale
  const e = p.x >= s.w && p.x <= s.w + out && p.y >= 0 && p.y <= s.h + out
  const so = p.y >= s.h && p.y <= s.h + out && p.x >= 0 && p.x <= s.w + out
  return e && so ? 'se' : e ? 'e' : so ? 's' : null
}

/** 画道路、画区块时鼠标所在的点：贴到线上，没贴上就取整 */
function drawPoint(sp: Pt, lines: SnapLines, free: boolean): Pt {
  const p = toContent(sp)
  if (free) return { x: round(p.x, 4), y: round(p.y, 4) }
  const s = snapPoint(p, lines, snapTol(), gridStep())
  return { x: round(s.x, 4), y: round(s.y, 4) }
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
  if (props.tool === 'road' || props.tool === 'block') {
    if (!freeEdit()) return
    // 画道路：路中心贴到区块的边、小区块之间的线上（穿过去），或者贴在离边半个路宽的地方（正好挨着区块）
    const lines = props.tool === 'road' ? snapLines(props.layout, [], props.roadWidth / 2, true) : snapLines(props.layout, [], props.roadWidth)
    const a = drawPoint(sp, lines, e.ctrlKey || e.metaKey)
    clearDragGhost()
    drag = { kind: props.tool, a, b: a, lines, started: false, sx: sp.x, sy: sp.y }
    return
  }
  const div = dividerAt(sp)
  if (div) {
    drag = { kind: 'resize', d: div, start: div.pos, value: div.pos }
    return
  }
  const additive = e.ctrlKey || e.metaKey || e.shiftKey
  if (freeEdit()) {
    const h = handleAt(sp)
    if (h) {
      const start = { ...blockItems.find((s) => s.block.id === h.id)!.rect }
      drag = { kind: 'bresize', id: h.id, handle: h.handle, start, rect: start, lines: snapLines(props.layout, [h.id], props.roadWidth) }
      return
    }
    const t = targetAt(sp)
    const id = t.kind === 'tick' ? null : blockIdOf(t)
    if (!id) {
      const edge = fieldEdgeAt(sp)
      if (edge) {
        const s = fieldSize(props.layout)
        const c = contentBounds(props.layout)
        drag = { kind: 'fresize', edge, w: s.w, h: s.h, min: { w: c ? c.x + c.w : MIN_SIZE, h: c ? c.y + c.h : MIN_SIZE } }
        return
      }
    } else if (!additive) {
      // 按住就能拖：没选中的先选中它，选中了几个的一起挪
      const ids = props.selectedBlocks.includes(id) ? [...props.selectedBlocks] : [id]
      if (!props.selectedBlocks.includes(id)) emit('update:selectedBlocks', ids)
      const rects = ids.map((i) => blockItems.find((s) => s.block.id === i)?.rect).filter((r): r is Rect => !!r).map((r) => ({ ...r }))
      drag = {
        kind: 'bmove', ids, hit: id, from: toContent(sp), rects, box: boundsOf(rects)!, lines: snapLines(props.layout, ids, props.roadWidth),
        dx: 0, dy: 0, copy: false, started: false, sx: sp.x, sy: sp.y,
      }
      return
    }
  }
  const target = targetAt(sp)
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
  const free = e.ctrlKey || e.metaKey
  if (d.kind === 'resize') {
    const raw = d.d.axis === 'col' ? p.x : p.y
    const step = niceStep(8 / view.scale)
    d.value = Math.min(d.d.max, Math.max(d.d.min, Math.round(raw / step) * step))
    drawResizeGhost(d)
    return
  }
  if (d.kind === 'bmove') {
    if (!d.started && !moved(d, sp)) return
    d.started = true
    let dx = p.x - d.from.x
    let dy = p.y - d.from.y
    // 按住 Shift 只横着或只竖着挪
    const lock = e.shiftKey ? (Math.abs(dx) >= Math.abs(dy) ? 'x' : 'y') : null
    if (lock === 'x') dy = 0
    if (lock === 'y') dx = 0
    let guides: Guide[] = []
    if (!free) {
      const s = snapMove({ ...d.box, x: d.box.x + dx, y: d.box.y + dy }, d.lines, snapTol(), gridStep())
      if (lock !== 'y') dx += s.dx
      if (lock !== 'x') dy += s.dy
      guides = s.guides.filter((g) => (g.axis === 'x' ? lock !== 'y' : lock !== 'x'))
    }
    d.dx = round(dx, 4)
    d.dy = round(dy, 4)
    d.copy = e.altKey
    drawMoveGhost(d, guides, sp)
    return
  }
  if (d.kind === 'bresize') {
    const min = Math.max(MIN_SIZE, 6 / view.scale)
    const r = resizeBy(d.start, d.handle, p, free ? NO_LINES : d.lines, snapTol(), min, free ? 0 : gridStep())
    d.rect = { x: round(r.rect.x, 4), y: round(r.rect.y, 4), w: round(r.rect.w, 4), h: round(r.rect.h, 4) }
    drawBlockResizeGhost(d, r.guides, sp)
    return
  }
  if (d.kind === 'fresize') {
    const step = free ? 0 : gridStep()
    const tidy = (v: number) => (step > 0 ? Math.round(v / step) * step : v)
    if (d.edge !== 's') d.w = round(Math.max(d.min.w, tidy(p.x)), 4)
    if (d.edge !== 'e') d.h = round(Math.max(d.min.h, tidy(p.y)), 4)
    drawFieldGhost(d, sp)
    return
  }
  if (d.kind === 'road' || d.kind === 'block') {
    if (!d.started && !moved(d, sp)) return
    d.started = true
    d.b = drawPoint(sp, d.lines, free)
    if (d.kind === 'road') drawRoadGhost(roadStrip(d.a, d.b, props.roadWidth), sp, true)
    else drawNewBlockGhost(rectBetween(d.a, d.b), sp)
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

const rectBetween = (a: Pt, b: Pt): Rect => ({ x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(b.x - a.x), h: Math.abs(b.y - a.y) })

function onPointerUp(e: PointerEvent) {
  const d = drag
  drag = null
  if (host.value?.hasPointerCapture(e.pointerId)) host.value.releasePointerCapture(e.pointerId)
  clearDragGhost()
  hideBand()
  hideTip()
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
    emit('resizeTrack', { axis: d.d.axis, index: d.d.index, a: round(d.d.a + delta, 4), b: round(d.d.b - delta, 4) })
    return
  }
  if (d.kind === 'bmove') {
    if (d.started && (d.dx !== 0 || d.dy !== 0)) emit('moveBlocks', d.ids, d.dx, d.dy, d.copy || e.altKey)
    // 没拖动：只是点了一下，选了几个时只留点的这个
    else if (!d.started && (props.selectedBlocks.length !== 1 || props.selectedBlocks[0] !== d.hit)) emit('update:selectedBlocks', [d.hit])
    return
  }
  if (d.kind === 'bresize') {
    const a = d.start
    const b = d.rect
    if (a.x !== b.x || a.y !== b.y || a.w !== b.w || a.h !== b.h) emit('resizeBlock', d.id, b)
    return
  }
  if (d.kind === 'fresize') {
    const s = fieldSize(props.layout)
    if (d.w !== s.w || d.h !== s.h) emit('resizeField', d.w, d.h)
    return
  }
  if (d.kind === 'road') {
    if (d.started) {
      const strip = roadStrip(d.a, d.b, props.roadWidth)
      if (Math.max(strip.rect.w, strip.rect.h) * view.scale >= 8) emit('drawRoad', strip.rect, strip.axis)
    } else {
      // 只是点了一下：点在区块的边或小区块之间的线上，加一条通到头的道路
      const line = roadLineAt(local(e))
      if (line) emit('drawRoad', line.rect, line.axis)
    }
    return
  }
  if (d.kind === 'block') {
    const r = rectBetween(d.a, d.b)
    if (d.started && r.w * view.scale >= 8 && r.h * view.scale >= 8) emit('drawBlock', r)
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
  hideTip()
  setCursor()
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

const CLAY = '#c0562f'

function clearDragGhost() {
  if (!stage) return
  dragGroup.destroyChildren()
  overlayLayer.batchDraw()
}

/** 提示放在鼠标旁边，靠近右边、下边时翻到另一侧 */
function tipNear(text: string, sp: Pt) {
  showTip(text, sp.x > size.w - 290 ? sp.x - 280 : sp.x + 16, sp.y > size.h - 120 ? sp.y - 96 : sp.y + 18)
}

function drawGuides(guides: Guide[]) {
  for (const g of guides) {
    const pad = px(14)
    const pts = g.axis === 'x' ? [g.pos, g.from - pad, g.pos, g.to + pad] : [g.from - pad, g.pos, g.to + pad, g.pos]
    dragGroup.add(new Konva.Line({ ...shape, points: pts, stroke: CLAY, strokeWidth: 1.2, dash: [5, 4], strokeScaleEnabled: false }))
  }
}

/** 进了区块以后拖小区块之间的线 */
function drawResizeGhost(d: Extract<Drag, { kind: 'resize' }>) {
  dragGroup.destroyChildren()
  const v = d.value
  const pts = d.d.axis === 'col' ? [v, d.d.from, v, d.d.to] : [d.d.from, v, d.d.to, v]
  dragGroup.add(new Konva.Line({ ...shape, points: pts, stroke: CLAY, strokeWidth: 2.5, strokeScaleEnabled: false }))
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const delta = v - d.start
  const name = d.d.axis === 'col' ? '列' : '行'
  const n = d.d.index + 1
  const s = toScreen(d.d.axis === 'col' ? { x: v, y: d.d.from } : { x: d.d.from, y: v })
  showTip(`第 ${n} ${name}：${fmt(d.d.a + delta, u)}　第 ${n + 1} ${name}：${fmt(d.d.b - delta, u)}`, s.x + 12, s.y + 12)
}

/** 和 rect 叠在一起的别的区块（道路和道路不算）；skip 是正在挪的 */
function clashWith(rect: Rect, kind: string, skip: string[]): LayoutBlock | null {
  for (const s of blockItems) {
    if (skip.includes(s.block.id)) continue
    if (kind === 'ROAD' && s.block.kind === 'ROAD') continue
    if (overlaps(rect, s.rect)) return s.block
  }
  return null
}

function drawMoveGhost(d: Extract<Drag, { kind: 'bmove' }>, guides: Guide[], sp: Pt) {
  dragGroup.destroyChildren()
  let clash: LayoutBlock | null = null
  d.rects.forEach((r, i) => {
    const moved: Rect = { x: r.x + d.dx, y: r.y + d.dy, w: r.w, h: r.h }
    const kind = props.layout.blocks.find((b) => b.id === d.ids[i])?.kind ?? 'PLANT'
    const hit = clashWith(moved, kind, d.copy ? [] : d.ids)
    clash = clash ?? hit
    dragGroup.add(new Konva.Rect({
      ...shape, ...box(moved), fill: hit ? 'rgba(192, 86, 47, 0.25)' : 'rgba(226, 182, 90, 0.35)', stroke: hit ? CLAY : WHEAT,
      strokeWidth: 2.5, dash: [6, 4], strokeScaleEnabled: false,
    }))
  })
  drawGuides(guides)
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const lines = [`离左边 ${fmt(d.box.x + d.dx, u)} · 离上边 ${fmt(d.box.y + d.dy, u)}`]
  if (clash) lines.push(`和“${(clash as LayoutBlock).name}”叠在一起了，叠着不能保存`)
  lines.push(d.copy ? '松手复制一份放在这里' : '按住 Alt 松手是复制，Shift 只横着或竖着挪')
  tipNear(lines.join('\n'), sp)
}

function drawBlockResizeGhost(d: Extract<Drag, { kind: 'bresize' }>, guides: Guide[], sp: Pt) {
  dragGroup.destroyChildren()
  const b = props.layout.blocks.find((x) => x.id === d.id)
  const hit = b ? clashWith(d.rect, b.kind, [d.id]) : null
  dragGroup.add(new Konva.Rect({
    ...shape, ...box(d.rect), fill: hit ? 'rgba(192, 86, 47, 0.25)' : 'rgba(226, 182, 90, 0.35)', stroke: hit ? CLAY : WHEAT,
    strokeWidth: 2.5, dash: [6, 4], strokeScaleEnabled: false,
  }))
  drawGuides(guides)
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const lines = [`宽 ${fmt(d.rect.w, u)} × 长 ${fmt(d.rect.h, u)}`]
  if (b?.inner) {
    const one = plotSize(props.layout, { ...b, ...d.rect })
    lines.push(one
      ? `里面 ${b.inner.rows.length} 行 × ${b.inner.cols.length} 列，每个小区块 ${fmt(one.w, u)} × ${fmt(one.h, u)}`
      : `里面 ${b.inner.rows.length} 行 × ${b.inner.cols.length} 列小区块按比例跟着变`)
  }
  if (hit) lines.push(`和“${hit.name}”叠在一起了，叠着不能保存`)
  tipNear(lines.join('\n'), sp)
}

function drawFieldGhost(d: Extract<Drag, { kind: 'fresize' }>, sp: Pt) {
  dragGroup.destroyChildren()
  dragGroup.add(new Konva.Rect({
    ...shape, x: 0, y: 0, width: d.w, height: d.h, stroke: FOREST, strokeWidth: 2.5, dash: [8, 5], strokeScaleEnabled: false,
    fill: 'rgba(28, 66, 49, 0.06)',
  }))
  overlayLayer.batchDraw()
  const u = props.layout.unit
  tipNear(`地块 ${fmt(d.w, u)} × ${fmt(d.h, u)}\n不能比里面的区块、道路小`, sp)
}

function drawNewBlockGhost(r: Rect, sp: Pt) {
  dragGroup.destroyChildren()
  const hit = clashWith(r, 'PLANT', [])
  dragGroup.add(new Konva.Rect({
    ...shape, ...box(r), fill: hit ? 'rgba(192, 86, 47, 0.22)' : 'rgba(46, 107, 79, 0.2)', stroke: hit ? CLAY : '#2e6b4f',
    strokeWidth: 2.5, dash: [6, 4], strokeScaleEnabled: false,
  }))
  overlayLayer.batchDraw()
  const u = props.layout.unit
  const n = innerCountFor(props.layout, r.w, r.h)
  const lines = [`新区块 ${fmt(r.w, u)} × ${fmt(r.h, u)}`, `里面分 ${n.rows} 行 × ${n.cols} 列小区块（建好后可以改）`]
  if (hit) lines.push(`和“${hit.name}”叠在一起了，叠着不能保存`)
  tipNear(lines.join('\n'), sp)
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

// ---------- 道路工具、区块工具 ----------

/**
 * 道路工具下鼠标正对着哪条线：区块的边、小区块之间的线、地块的边。点一下在这条线上加一条通到地块两头的道路
 */
function roadLineAt(sp: Pt): { axis: Axis; rect: Rect; pos: number } | null {
  if (!freeEdit()) return null
  const L = props.layout
  const p = toContent(sp)
  const s = fieldSize(L)
  const tol = 9 / view.scale
  if (p.x < -tol || p.y < -tol || p.x > s.w + tol || p.y > s.h + tol) return null
  const lines = snapLines(L, [], 0, true)
  const facing = (list: SnapLine[], along: number) => list.filter((l) => along >= l.from - tol && along <= l.to + tol)
  const lx = nearestLine(p.x, facing(lines.xs, p.y), tol)
  const ly = nearestLine(p.y, facing(lines.ys, p.x), tol)
  const w = props.roadWidth
  if (lx && (!ly || Math.abs(lx.pos - p.x) <= Math.abs(ly.pos - p.y))) {
    return { axis: 'col', pos: lx.pos, rect: { x: lx.pos - w / 2, y: 0, w, h: s.h } }
  }
  if (ly) return { axis: 'row', pos: ly.pos, rect: { x: 0, y: ly.pos - w / 2, w: s.w, h: w } }
  return null
}

/** 画出这条道路放下去的样子，说明会切开、挪动什么。dragging 是正拖着画，否则是点一下加通到头的 */
function drawRoadGhost(strip: { rect: Rect; axis: Axis }, sp: Pt, dragging: boolean) {
  dragGroup.destroyChildren()
  const L = props.layout
  const u = L.unit
  const res = drawRoad(L, strip.rect, strip.axis)
  const made = res.id ? res.layout.blocks.find((b) => b.id === res.id) : null
  const r: Rect = made ? { x: made.x, y: made.y, w: made.w, h: made.h } : strip.rect
  const row = strip.axis === 'row'
  if (made && (res.cut > 0 || res.moved > 0)) {
    // 切开、往后挪的那条线
    const pts = row ? [r.x, r.y, r.x + r.w, r.y] : [r.x, r.y, r.x, r.y + r.h]
    dragGroup.add(new Konva.Line({ ...shape, points: pts, stroke: CLAY, strokeWidth: 2.5, strokeScaleEnabled: false }))
  }
  dragGroup.add(new Konva.Rect({
    ...shape, ...box(r), fill: made ? 'rgba(217, 207, 185, 0.9)' : 'rgba(192, 86, 47, 0.2)', stroke: made ? '#8a6118' : CLAY,
    strokeWidth: 1.8, dash: [6, 4], strokeScaleEnabled: false,
  }))
  if (made) dragGroup.add(new Konva.Line({ ...shape, points: roadLine(r), stroke: '#ffffff', strokeWidth: 1.5, dash: [8, 6], strokeScaleEnabled: false }))
  overlayLayer.batchDraw()
  if (!made) {
    tipNear(res.problem ?? '画不了', sp)
    return
  }
  const lines = [`${dragging ? '松手' : '点一下'}加一条${row ? '横向' : '纵向'}道路：长 ${fmt(row ? r.w : r.h, u)}，宽 ${fmt(row ? r.h : r.w, u)}`]
  if (res.cut > 0 || res.moved > 0) {
    const parts = []
    if (res.cut > 0) parts.push(`切开 ${res.cut} 个区块`)
    if (res.moved > 0) parts.push(`后面的往${row ? '下' : '右'}挪 ${fmt(row ? r.h : r.w, u)}`)
    lines.push(`${parts.join('，')}；小区块大小都不变`)
  } else {
    lines.push(res.grew > 0 ? '放在地块边上，区块都不动' : '放在空地上，别的都不动')
  }
  if (res.grew > 0) lines.push(`地块${row ? '长' : '宽'}变成 ${fmt(row ? res.layout.h : res.layout.w, u)}`)
  tipNear(lines.join('\n'), sp)
}

function roadHover(sp: Pt) {
  hoverKey = ''
  hoverRect.visible(false)
  if (!freeEdit()) {
    clearDragGhost()
    tipNear('道路画在整块地上：先回到整块地（Esc）', sp)
    return
  }
  const line = roadLineAt(sp)
  if (line) {
    drawRoadGhost(line, sp, false)
    setCursor('copy')
    return
  }
  clearDragGhost()
  setCursor('crosshair')
  const p = toContent(sp)
  const s = fieldSize(props.layout)
  const pad = 30 / view.scale
  if (p.x >= -pad && p.y >= -pad && p.x <= s.w + pad && p.y <= s.h + pad) {
    tipNear('按住拖动：从哪儿拖到哪儿，路就画到哪儿\n把鼠标移到区块的边或小区块之间的线上点一下：加一条通到头的', sp)
  } else {
    hideTip()
  }
}

function blockHover(sp: Pt) {
  hoverKey = ''
  hoverRect.visible(false)
  overlayLayer.batchDraw()
  setCursor('crosshair')
  if (!freeEdit()) tipNear('区块画在整块地上：先回到整块地（Esc）', sp)
  else tipNear('按住拖出一个方框，建一个种植区块（画到地块外面，地块跟着变大）', sp)
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

/** 拉手朝哪个方向就用哪个方向的箭头；画面转了以后跟着转 */
function handleCursor(h: Handle): string {
  const order: Handle[] = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw']
  const cursors = ['ns-resize', 'nesw-resize', 'ew-resize', 'nwse-resize']
  const turn = Math.round(props.rotation / 45)
  return cursors[(((order.indexOf(h) + turn) % 4) + 4) % 4]!
}

function plainHover() {
  if (hoverKey || hoverRect.visible()) {
    hoverKey = ''
    hoverRect.visible(false)
    overlayLayer.batchDraw()
  }
}

function hoverAt(sp: { x: number; y: number }) {
  if (props.tool === 'road') {
    roadHover(sp)
    return
  }
  if (props.tool === 'block') {
    blockHover(sp)
    return
  }
  const div = dividerAt(sp)
  if (div) {
    plainHover()
    setCursor(div.axis === 'col' ? 'col-resize' : 'row-resize')
    showTip(`拖动改${div.axis === 'col' ? '宽' : '高'}`, sp.x + 14, sp.y + 14)
    return
  }
  const handle = handleAt(sp)
  if (handle) {
    plainHover()
    setCursor(handleCursor(handle.handle))
    showTip('拖动改大小，里面的小区块按比例跟着变', sp.x + 14, sp.y + 14)
    return
  }
  const t = targetAt(sp)
  if (t.kind === 'none') {
    const edge = fieldEdgeAt(sp)
    if (edge) {
      plainHover()
      setCursor(handleCursor(edge))
      showTip('拖动改地块的大小', sp.x + 14, sp.y + 14)
      return
    }
  }
  let rect: Rect | null = null
  let text = ''
  let key = ''
  if (t.kind === 'cell') {
    key = `c:${t.cell.blockId}:${t.cell.key}`
    rect = inset(t.cell.rect)
    text = t.cell.plotId != null ? props.cells.get(t.cell.plotId)?.tooltip ?? '' : ''
    if (t.cell.kind !== 'PLANT') text = [text.split('\n')[0] ?? '', CELL_KIND[t.cell.kind]].filter(Boolean).join('\n')
    if (!text && t.cell.plotId == null) text = t.cell.kind === 'PLANT' ? '新小区块（保存布局后建好）' : CELL_KIND[t.cell.kind]
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
  if (props.pick === 'block' && id && !focusBlock()) return 'move'
  if (props.pick === 'cell' && t.kind === 'cell' && props.selectedCells.includes(t.cell.key)) return 'move'
  return t.kind === 'tick' ? 'pointer' : 'default'
}

function blockTip(id: string): string {
  const b = props.layout.blocks.find((x) => x.id === id)
  if (!b) return ''
  const u = props.layout.unit
  const r = blockRect(props.layout, b)
  const lines = [`${b.name}`, `${fmt(r.w, u)} × ${fmt(r.h, u)}`]
  if (b.inner) {
    const n = anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges).length
    const one = plotSize(props.layout, b)
    lines.push(`${b.inner.rows.length} 行 × ${b.inner.cols.length} 列，${n} 个小区块${one ? `，每个 ${fmt(one.w, u)} × ${fmt(one.h, u)}` : ''}`)
  }
  if (props.editable && props.pick === 'block') {
    lines.push(b.kind === 'PLANT' ? '按住拖动挪位置 · 双击进去改小区块' : '按住拖动挪位置 · 选中后拖四周的小方块改大小')
  } else if (!focusBlock() && b.kind === 'PLANT') {
    lines.push('双击名称牌进入区块')
  }
  return lines.join('\n')
}

function onLeave() {
  if ((props.tool === 'road' || props.tool === 'block') && !drag) clearDragGhost()
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
  if ((props.tool === 'road' || props.tool === 'block') && !c && !space.on) v = 'crosshair'
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
  chromeGroup.visible(false)
  hoverRect.visible(false)
  await nextTick()
  try {
    saveFile(stage.toDataURL({ mimeType: 'image/jpeg', quality: 0.92, pixelRatio: 2 }), filename)
  } finally {
    white.destroy()
    selGroup.visible(true)
    chromeGroup.visible(true)
    stage.batchDraw()
  }
}

/** 一个点（布局里的坐标）在画布上的位置，给外面放方位字用 */
function screenOf(p: { x: number; y: number }) {
  return toScreen(p)
}

/** 屏幕上这个点是哪个区块、哪个小区块（拖品系到平面图上种植时用）；onTag 表示在区块名称牌上 */
function hitAt(clientX: number, clientY: number): { plotId?: number; blockId?: string; onTag: boolean } | null {
  if (!host.value) return null
  const r = host.value.getBoundingClientRect()
  const t = targetAt({ x: clientX - r.left, y: clientY - r.top })
  if (t.kind === 'tag') return { blockId: t.blockId, onTag: true }
  if (t.kind === 'cell') return { plotId: t.cell.plotId ?? undefined, blockId: t.cell.blockId, onTag: false }
  if (t.kind === 'block') return { blockId: t.item.block.id, onTag: false }
  return null
}

defineExpose({ fit, zoomBy, zoomToPlots, zoomToRect, exportJpeg, screenOf, contentRect, clearMeasure, hitAt })

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
  chromeGroup = new Konva.Group(shape)
  conflictGroup = new Konva.Group(shape)
  linkGroup = new Konva.Group(shape)
  dragGroup = new Konva.Group(shape)
  measureGroup = new Konva.Group(shape)
  hoverRect = new Konva.Rect({ ...shape, visible: false, stroke: 'rgba(28, 66, 49, 0.75)', strokeWidth: 2, strokeScaleEnabled: false, fill: 'rgba(255, 255, 255, 0.18)' })
  overlayLayer.add(linkGroup, conflictGroup, hoverRect, selGroup, chromeGroup, dragGroup, measureGroup)
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

watch(() => [props.layout, props.cells, props.focus, props.pick], () => {
  scheduleBuild()
  if (props.tool === 'road' || props.tool === 'block') clearDragGhost()
})
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
  clearDragGhost()
  hideTip()
  // 小方块只在选择工具下有
  drawChrome()
  overlayLayer.batchDraw()
  setCursor()
})
watch(() => props.insets, () => { if (!touched) fit() }, { deep: true })
// 地块大小变了（画了道路、把区块挪到了外面、拖了地块的边）时，没手动缩放过就重新放好
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
  bottom: 74px;
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
