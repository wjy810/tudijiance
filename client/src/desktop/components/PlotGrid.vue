<script setup lang="ts">
// 实验田平面图：小区网格、选择、对照关系虚线、传感器位置。用 Konva 画在画布上，可以导出 jpg 种植图
import { computed, ref } from 'vue'
import type Konva from 'konva'
import type { KonvaEventObject } from 'konva/lib/Node'

import type { GridCell, GridDevice, GridLink } from './plotGrid'

const props = defineProps<{
  rows: number
  cols: number
  cells: GridCell[]
  selected: number[]
  links: GridLink[]
  devices: GridDevice[]
  zoom: number
}>()

const emit = defineEmits<{
  'update:selected': [ids: number[]]
}>()

const CELL_W = 104
const CELL_H = 70
const HEADER = 30
const PAD = 10
const FONT = "'Microsoft YaHei', 'PingFang SC', 'WenQuanYi Zen Hei', sans-serif"

const stageRef = ref<{ getNode: () => Konva.Stage } | null>(null)

const width = computed(() => PAD * 2 + HEADER + props.cols * CELL_W)
const height = computed(() => PAD * 2 + HEADER + props.rows * CELL_H)
const selectedSet = computed(() => new Set(props.selected))
const cellByPlot = computed(() => new Map(props.cells.map((c) => [c.plotId, c])))

const cellX = (col: number) => PAD + HEADER + (col - 1) * CELL_W
const cellY = (row: number) => PAD + HEADER + (row - 1) * CELL_H
const center = (c: GridCell) => ({ x: cellX(c.colNo) + CELL_W / 2, y: cellY(c.rowNo) + CELL_H / 2 })

const colHeaders = computed(() => Array.from({ length: props.cols }, (_, i) => i + 1))
const rowHeaders = computed(() => Array.from({ length: props.rows }, (_, i) => i + 1))

const linkShapes = computed(() =>
  props.links.flatMap((link) =>
    link.segments.flatMap((s, i) => {
      const a = cellByPlot.value.get(s.from)
      const b = cellByPlot.value.get(s.to)
      if (!a || !b) {
        return []
      }
      const p1 = center(a)
      const p2 = center(b)
      return [
        {
          key: `${link.id}-${i}`,
          points: [p1.x, p1.y, p2.x, p2.y],
          label: link.label,
          mid: { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 },
          highlighted: link.highlighted,
        },
      ]
    }),
  ),
)

const DEVICE_COLORS: Record<string, string> = { ONLINE: '#2f9e44', OFFLINE: '#868e96', NEVER: '#f08c00' }

const deviceShapes = computed(() =>
  props.devices.map((d) => ({
    ...d,
    x: PAD + HEADER + (d.col - 0.5) * CELL_W,
    y: PAD + HEADER + (d.row - 0.5) * CELL_H,
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
  const col = Math.floor((x - PAD - HEADER) / CELL_W) + 1
  const row = Math.floor((y - PAD - HEADER) / CELL_H) + 1
  return props.cells.find((c) => c.rowNo === row && c.colNo === col)
}

function onDown() {
  pressStart = pointer()
  dragging = false
}

function onMove() {
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
        return cx < right && cx + CELL_W > left && cy < bottom && cy + CELL_H > top
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
    const col = Math.floor((x - PAD - HEADER) / CELL_W) + 1
    select(props.cells.filter((c) => c.colNo === col).map((c) => c.plotId), additive)
    return
  }
  if (inRowHeader) {
    const row = Math.floor((y - PAD - HEADER) / CELL_H) + 1
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
}

function select(ids: number[], additive: boolean) {
  emit('update:selected', additive ? [...new Set([...props.selected, ...ids])] : ids)
}

/** 导出 jpg 种植图（标准 8.4 要求播种后 7 天内上报）。 */
function exportJpeg(filename: string) {
  const stage = stageRef.value?.getNode()
  if (!stage) {
    return
  }
  const url = stage.toDataURL({ mimeType: 'image/jpeg', quality: 0.92, pixelRatio: 2 / props.zoom })
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
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
  >
    <v-layer>
      <!-- 白色背景：jpg 没有透明通道 -->
      <v-rect :config="{ x: 0, y: 0, width, height, fill: '#ffffff' }" />

      <v-text
        v-for="c in colHeaders"
        :key="'c' + c"
        :config="{
          x: cellX(c), y: PAD, width: CELL_W, height: HEADER, text: String(c),
          align: 'center', verticalAlign: 'middle', fontSize: 13, fill: '#6b7570', fontFamily: FONT,
        }"
      />
      <v-text
        v-for="r in rowHeaders"
        :key="'r' + r"
        :config="{
          x: PAD, y: cellY(r), width: HEADER, height: CELL_H, text: String(r),
          align: 'center', verticalAlign: 'middle', fontSize: 13, fill: '#6b7570', fontFamily: FONT,
        }"
      />

      <v-group v-for="c in cells" :key="c.plotId">
        <v-rect
          :config="{
            x: cellX(c.colNo) + 2, y: cellY(c.rowNo) + 2, width: CELL_W - 4, height: CELL_H - 4,
            fill: c.fill, cornerRadius: 4,
            stroke: selectedSet.has(c.plotId) ? '#1c64d6' : '#c9d3cc',
            strokeWidth: selectedSet.has(c.plotId) ? 3 : 1,
          }"
        />
        <v-text
          :config="{
            x: cellX(c.colNo) + 4, y: cellY(c.rowNo) + 8, width: CELL_W - 8, height: CELL_H - 12,
            text: c.lines.join('\n'), align: 'center', fontSize: 12, lineHeight: 1.35,
            fill: '#1f2a24', fontFamily: FONT, wrap: 'none', ellipsis: true,
          }"
        />
        <v-text
          v-if="c.badge"
          :config="{
            x: cellX(c.colNo) + CELL_W - 30, y: cellY(c.rowNo) + 5, width: 24, text: c.badge,
            align: 'right', fontSize: 11, fontStyle: 'bold', fill: '#c92a2a', fontFamily: FONT,
          }"
        />
      </v-group>

      <v-group v-for="l in linkShapes" :key="l.key">
        <v-line
          :config="{
            points: l.points, stroke: l.highlighted ? '#d9480f' : '#f0a070',
            strokeWidth: l.highlighted ? 2.5 : 1.5, dash: [8, 6], lineCap: 'round',
          }"
        />
        <v-label :config="{ x: l.mid.x, y: l.mid.y - 10 }">
          <v-tag :config="{ fill: '#fff4e6', stroke: '#f0a070', cornerRadius: 3 }" />
          <v-text
            :config="{ text: l.label, fontSize: 11, padding: 3, fill: '#d9480f', fontFamily: FONT }"
          />
        </v-label>
      </v-group>

      <v-group v-for="d in deviceShapes" :key="'d' + d.id">
        <v-circle :config="{ x: d.x, y: d.y, radius: 8, fill: d.color, stroke: '#ffffff', strokeWidth: 2 }" />
        <v-label :config="{ x: d.x + 10, y: d.y - 8 }">
          <v-tag :config="{ fill: '#ffffff', opacity: 0.9, cornerRadius: 3 }" />
          <v-text :config="{ text: d.label, fontSize: 11, padding: 2, fill: '#1f2a24', fontFamily: FONT }" />
        </v-label>
      </v-group>

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
