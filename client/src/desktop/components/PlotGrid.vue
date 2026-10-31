<script setup lang="ts">
// 实验田平面图：小区网格、选择、对照关系虚线、传感器位置。用 Konva 画在画布上，可以导出 jpg 种植图
// 每列的宽、每行的高可以分别设置；panning 为 true 时鼠标拖动用来移动画面，不做选择
import { computed, nextTick, ref } from 'vue'
import type Konva from 'konva'
import type { KonvaEventObject } from 'konva/lib/Node'

import { saveFile } from '@/shared/api/http'
import type { GridCell, GridDevice, GridLink } from './plotGrid'

const props = defineProps<{
  rows: number
  cols: number
  cells: GridCell[]
  selected: number[]
  links: GridLink[]
  devices: GridDevice[]
  zoom: number
  /** 每列的宽（像素），不传用默认值 */
  colWidths?: number[]
  /** 每行的高（像素），不传用默认值 */
  rowHeights?: number[]
  panning?: boolean
}>()

const emit = defineEmits<{
  'update:selected': [ids: number[]]
  /** 右键点了小区，x、y 是窗口里的位置，用来弹出菜单 */
  contextmenu: [plotId: number, x: number, y: number]
  /** 双击小区 */
  open: [plotId: number]
}>()

const CELL_W = 104
const CELL_H = 70
const HEADER = 30
const PAD = 10
const FONT = "'Microsoft YaHei', 'PingFang SC', 'WenQuanYi Zen Hei', sans-serif"

const stageRef = ref<{ getNode: () => Konva.Stage } | null>(null)

/** 第 i 列（从 1 开始）左边的位置，最后一项是总宽 */
function offsets(count: number, sizes: number[] | undefined, fallback: number): number[] {
  const result = [0]
  for (let i = 0; i < count; i++) {
    result.push(result[i]! + (sizes?.[i] ?? fallback))
  }
  return result
}

const colOffsets = computed(() => offsets(props.cols, props.colWidths, CELL_W))
const rowOffsets = computed(() => offsets(props.rows, props.rowHeights, CELL_H))

const width = computed(() => PAD * 2 + HEADER + colOffsets.value[props.cols]!)
const height = computed(() => PAD * 2 + HEADER + rowOffsets.value[props.rows]!)
const selectedSet = computed(() => new Set(props.selected))
const cellByPlot = computed(() => new Map(props.cells.map((c) => [c.plotId, c])))

const cellX = (col: number) => PAD + HEADER + (colOffsets.value[col - 1] ?? 0)
const cellY = (row: number) => PAD + HEADER + (rowOffsets.value[row - 1] ?? 0)
const cellW = (col: number) => props.colWidths?.[col - 1] ?? CELL_W
const cellH = (row: number) => props.rowHeights?.[row - 1] ?? CELL_H
const center = (c: GridCell) => ({ x: cellX(c.colNo) + cellW(c.colNo) / 2, y: cellY(c.rowNo) + cellH(c.rowNo) / 2 })
const linkAnchor = (c: GridCell) => ({ x: cellX(c.colNo) + cellW(c.colNo) / 2, y: cellY(c.rowNo) + cellH(c.rowNo) - 9 })

/** 位置落在第几格（从 1 开始），不在网格里返回 0 */
function indexAt(pos: number, offs: number[]): number {
  for (let i = 1; i < offs.length; i++) {
    if (pos >= offs[i - 1]! && pos < offs[i]!) {
      return i
    }
  }
  return 0
}

const colHeaders = computed(() => Array.from({ length: props.cols }, (_, i) => i + 1))
const rowHeaders = computed(() => Array.from({ length: props.rows }, (_, i) => i + 1))

// 对照虚线：对照小区连到各个比较的小区
const linkLines = computed(() =>
  props.links.flatMap((link) =>
    link.segments.flatMap((s, i) => {
      const a = cellByPlot.value.get(s.from)
      const b = cellByPlot.value.get(s.to)
      if (!a || !b) {
        return []
      }
      // 连在小区下沿的空白处，不压住小区里的文字
      const p1 = linkAnchor(a)
      const p2 = linkAnchor(b)
      return [{ key: `${link.id}-${i}`, points: [p1.x, p1.y, p2.x, p2.y] }]
    }),
  ),
)

// 每组只标一次，标在对照小区的上沿
const linkLabels = computed(() =>
  props.links.flatMap((link) =>
    link.controlPlotIds.flatMap((plotId) => {
      const c = cellByPlot.value.get(plotId)
      return c ? [{ key: `${link.id}-${plotId}`, text: `${link.label}（对照）`, x: center(c).x, y: cellY(c.rowNo) - 9 }] : []
    }),
  ),
)

// 和显示中的对照关系有关的小区画一圈描边：对照小区实线，比较的小区虚线
const linkRings = computed(() => {
  const rings = new Map<number, boolean>()
  for (const link of props.links) {
    for (const s of link.segments) {
      rings.set(s.to, rings.get(s.to) ?? false)
    }
    for (const id of link.controlPlotIds) {
      rings.set(id, true)
    }
  }
  return [...rings].flatMap(([plotId, control]) => {
    const c = cellByPlot.value.get(plotId)
    return c ? [{ plotId, control, x: cellX(c.colNo), y: cellY(c.rowNo), w: cellW(c.colNo), h: cellH(c.rowNo) }] : []
  })
})

const DEVICE_COLORS: Record<string, string> = { ONLINE: '#2f9e44', OFFLINE: '#868e96', NEVER: '#f08c00' }

// 设备位置可以是小数（在两格之间），按所在格子的宽高换算
function along(pos: number, offs: number[]): number {
  const i = Math.min(Math.max(Math.floor(pos - 0.5), 0), offs.length - 2)
  const size = offs[i + 1]! - offs[i]!
  return offs[i]! + (pos - 0.5 - i) * size + size / 2
}

const deviceShapes = computed(() =>
  props.devices.map((d) => ({
    ...d,
    x: PAD + HEADER + along(d.col, colOffsets.value),
    y: PAD + HEADER + along(d.row, rowOffsets.value),
    color: DEVICE_COLORS[d.status] ?? '#868e96',
  })),
)

// ---------- 鼠标选择 ----------
// 单击选中一个小区；按住 Ctrl 或 Shift 单击可以多选；拖动鼠标框选；点行号、列号选中整行、整列
const band = ref<{ x1: number; y1: number; x2: number; y2: number } | null>(null)
let pressStart: { x: number; y: number } | null = null
let dragging = false

function pointer(): { x: number; y: number } | null {
  const pos = stageRef.value?.getNode().getPointerPosition()
  return pos ? { x: pos.x / props.zoom, y: pos.y / props.zoom } : null
}

function hitCell(x: number, y: number): GridCell | undefined {
  const col = indexAt(x - PAD - HEADER, colOffsets.value)
  const row = indexAt(y - PAD - HEADER, rowOffsets.value)
  return props.cells.find((c) => c.rowNo === row && c.colNo === col)
}

function onDown(e: KonvaEventObject<MouseEvent>) {
  // 只有左键做选择；右键弹菜单
  if (props.panning || e.evt.button !== 0) {
    return
  }
  pressStart = pointer()
  dragging = false
}

function onMove() {
  updateHover()
  if (!pressStart) {
    return
  }
  const p = pointer()
  if (!p) {
    return
  }
  if (!dragging && Math.hypot(p.x - pressStart.x, p.y - pressStart.y) > 5) {
    dragging = true
  }
  if (dragging) {
    band.value = { x1: pressStart.x, y1: pressStart.y, x2: p.x, y2: p.y }
  }
}

function onUp(e: KonvaEventObject<MouseEvent>) {
  const additive = e.evt.ctrlKey || e.evt.metaKey || e.evt.shiftKey
  const start = pressStart
  pressStart = null
  if (!start) {
    return
  }
  if (dragging && band.value) {
    const { x1, y1, x2, y2 } = band.value
    const [left, right] = [Math.min(x1, x2), Math.max(x1, x2)]
    const [top, bottom] = [Math.min(y1, y2), Math.max(y1, y2)]
    const hits = props.cells
      .filter((c) => {
        const cx = cellX(c.colNo)
        const cy = cellY(c.rowNo)
        return cx < right && cx + cellW(c.colNo) > left && cy < bottom && cy + cellH(c.rowNo) > top
      })
      .map((c) => c.plotId)
    band.value = null
    dragging = false
    select(hits, additive)
    return
  }

  const { x, y } = start
  const inColHeader = y >= PAD && y < PAD + HEADER && x >= PAD + HEADER
  const inRowHeader = x >= PAD && x < PAD + HEADER && y >= PAD + HEADER
  if (inColHeader) {
    const col = indexAt(x - PAD - HEADER, colOffsets.value)
    select(props.cells.filter((c) => c.colNo === col).map((c) => c.plotId), additive)
    return
  }
  if (inRowHeader) {
    const row = indexAt(y - PAD - HEADER, rowOffsets.value)
    select(props.cells.filter((c) => c.rowNo === row).map((c) => c.plotId), additive)
    return
  }
  const cell = hitCell(x, y)
  if (!cell) {
    if (!additive) {
      emit('update:selected', [])
    }
    return
  }
  if (additive) {
    const next = new Set(props.selected)
    if (next.has(cell.plotId)) {
      next.delete(cell.plotId)
    } else {
      next.add(cell.plotId)
    }
    emit('update:selected', [...next])
  } else {
    emit('update:selected', [cell.plotId])
  }
}

function onLeave() {
  pressStart = null
  dragging = false
  band.value = null
  hover.value = null
}

function onContextMenu(e: KonvaEventObject<MouseEvent>) {
  e.evt.preventDefault()
  const p = pointer()
  const cell = p ? hitCell(p.x, p.y) : undefined
  if (!cell) {
    return
  }
  // 右键点的小区不在已选范围里时，改成只选它
  if (!selectedSet.value.has(cell.plotId)) {
    emit('update:selected', [cell.plotId])
  }
  emit('contextmenu', cell.plotId, e.evt.clientX, e.evt.clientY)
}

function onDblClick() {
  const p = pointer()
  const cell = p ? hitCell(p.x, p.y) : undefined
  if (cell && !props.panning) {
    emit('open', cell.plotId)
  }
}

// ---------- 鼠标停在小区上时的说明 ----------
const hover = ref<{ text: string; x: number; y: number } | null>(null)

function updateHover() {
  const p = pointer()
  const cell = p && !pressStart ? hitCell(p.x, p.y) : undefined
  if (!cell?.tooltip || !p) {
    hover.value = null
    return
  }
  // 靠近右边、下边时把说明放到鼠标的另一侧，免得被画布边缘挡住
  const x = p.x > width.value - 200 ? p.x - 190 : p.x + 14
  const y = p.y > height.value - 90 ? p.y - 80 : p.y + 14
  hover.value = { text: cell.tooltip, x, y }
}

function select(ids: number[], additive: boolean) {
  emit('update:selected', additive ? [...new Set([...props.selected, ...ids])] : ids)
}

/** 导出 jpg 种植图（标准 8.4 要求播种后 7 天内上报）。 */
async function exportJpeg(filename: string) {
  const stage = stageRef.value?.getNode()
  if (!stage) {
    return
  }
  hover.value = null
  await nextTick()
  saveFile(stage.toDataURL({ mimeType: 'image/jpeg', quality: 0.92, pixelRatio: 2 / props.zoom }), filename)
}

defineExpose({ exportJpeg })
</script>

<template>
  <v-stage
    ref="stageRef"
    :config="{ width: width * zoom, height: height * zoom, scaleX: zoom, scaleY: zoom }"
    @mousedown="onDown"
    @mousemove="onMove"
    @mouseup="onUp"
    @mouseleave="onLeave"
    @contextmenu="onContextMenu"
    @dblclick="onDblClick"
  >
    <v-layer>
      <!-- 白色背景：jpg 没有透明通道 -->
      <v-rect :config="{ x: 0, y: 0, width, height, fill: '#ffffff' }" />

      <v-text
        v-for="c in colHeaders"
        :key="'c' + c"
        :config="{
          x: cellX(c), y: PAD, width: cellW(c), height: HEADER, text: String(c),
          align: 'center', verticalAlign: 'middle', fontSize: 13, fill: '#6b7570', fontFamily: FONT,
        }"
      />
      <v-text
        v-for="r in rowHeaders"
        :key="'r' + r"
        :config="{
          x: PAD, y: cellY(r), width: HEADER, height: cellH(r), text: String(r),
          align: 'center', verticalAlign: 'middle', fontSize: 13, fill: '#6b7570', fontFamily: FONT,
        }"
      />

      <v-group v-for="c in cells" :key="c.plotId">
        <v-rect
          :config="{
            x: cellX(c.colNo) + 2, y: cellY(c.rowNo) + 2, width: cellW(c.colNo) - 4, height: cellH(c.rowNo) - 4,
            fill: c.fill, cornerRadius: 4,
            stroke: selectedSet.has(c.plotId) ? '#1c64d6' : '#c9d3cc',
            strokeWidth: selectedSet.has(c.plotId) ? 3 : 1,
          }"
        />
        <v-text
          :config="{
            x: cellX(c.colNo) + 4, y: cellY(c.rowNo) + 8, width: cellW(c.colNo) - 8, height: cellH(c.rowNo) - 12,
            text: c.lines.join('\n'), align: 'center', fontSize: 12, lineHeight: 1.35,
            fill: '#1f2a24', fontFamily: FONT, wrap: 'none', ellipsis: true,
          }"
        />
        <v-text
          v-if="c.badge"
          :config="{
            x: cellX(c.colNo) + cellW(c.colNo) - 34, y: cellY(c.rowNo) + 5, width: 28, text: c.badge,
            align: 'right', fontSize: 11, fontStyle: 'bold', fill: c.badgeColor ?? '#c92a2a', fontFamily: FONT,
          }"
        />
        <v-circle
          v-if="c.marker"
          :config="{ x: cellX(c.colNo) + 10, y: cellY(c.rowNo) + cellH(c.rowNo) - 10, radius: 4, fill: '#1c7ed6' }"
        />
      </v-group>

      <v-rect
        v-for="r in linkRings"
        :key="'ring' + r.plotId"
        :config="{
          x: r.x + 1, y: r.y + 1, width: r.w - 2, height: r.h - 2, cornerRadius: 5, listening: false,
          stroke: '#d9480f', strokeWidth: r.control ? 2.5 : 1.5, dash: r.control ? undefined : [5, 4],
        }"
      />
      <v-line
        v-for="l in linkLines"
        :key="l.key"
        :config="{ points: l.points, stroke: '#d9480f', strokeWidth: 2, dash: [8, 6], lineCap: 'round', listening: false }"
      />
      <v-label v-for="l in linkLabels" :key="l.key" :config="{ x: l.x, y: l.y, listening: false }">
        <v-tag :config="{ fill: '#fff4e6', stroke: '#d9480f', cornerRadius: 3, pointerDirection: 'down', pointerWidth: 8, pointerHeight: 5 }" />
        <v-text :config="{ text: l.text, fontSize: 11, padding: 3, fill: '#c2410c', fontFamily: FONT }" />
      </v-label>

      <v-group v-for="d in deviceShapes" :key="'d' + d.id">
        <v-circle :config="{ x: d.x, y: d.y, radius: 8, fill: d.color, stroke: '#ffffff', strokeWidth: 2 }" />
        <v-label :config="{ x: d.x + 10, y: d.y - 8 }">
          <v-tag :config="{ fill: '#ffffff', opacity: 0.9, cornerRadius: 3 }" />
          <v-text :config="{ text: d.label, fontSize: 11, padding: 2, fill: '#1f2a24', fontFamily: FONT }" />
        </v-label>
      </v-group>

      <v-label v-if="hover" :config="{ x: hover.x, y: hover.y }">
        <v-tag :config="{ fill: '#1f2a24', opacity: 0.88, cornerRadius: 4 }" />
        <v-text :config="{ text: hover.text, fontSize: 12, padding: 6, lineHeight: 1.4, fill: '#ffffff', fontFamily: FONT }" />
      </v-label>

      <v-rect
        v-if="band"
        :config="{
          x: Math.min(band.x1, band.x2), y: Math.min(band.y1, band.y2),
          width: Math.abs(band.x2 - band.x1), height: Math.abs(band.y2 - band.y1),
          fill: 'rgba(28, 100, 214, 0.08)', stroke: '#1c64d6', strokeWidth: 1, dash: [4, 4],
        }"
      />
    </v-layer>
  </v-stage>
</template>
