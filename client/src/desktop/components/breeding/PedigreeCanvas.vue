<script setup lang="ts">
// 系谱图：按季分列，连线从父母指向子代（母本实线、父本虚线、选株点线）。
// 品系是一个个小卡片（HTML，能拖到平面图上种），连线画在下面的 SVG 里。
// 选中一个品系时，它的祖先和后代高亮，其余变淡。
import { computed } from 'vue'

import type { LineRow, Season } from '@/shared/api'
import { LINE_KIND } from '@/shared/labels'
import {
  ancestors, childrenMap, descendants, latestGeneration, layoutGraph, type GraphOptions,
} from '@/shared/pedigree'

const props = withDefaults(defineProps<{
  lines: LineRow[]
  seasons: Season[]
  options?: GraphOptions
  selectedId?: number | null
  /** 第二个选中的（杂交时的父本） */
  secondId?: number | null
  /** 额外标出的品系，例如平面图上选中的小区块种的 */
  marked?: Set<number> | null
  compact?: boolean
  /** 品系卡片能拖到平面图上 */
  draggable?: boolean
  zoom?: number
}>(), {
  options: () => ({}), selectedId: null, secondId: null, marked: null, compact: false, draggable: false, zoom: 1,
})

const emit = defineEmits<{
  select: [id: number, additive: boolean]
  open: [id: number]
  toggle: [id: number]
  dragline: [id: number]
}>()

const NODE_W = computed(() => (props.compact ? 124 : 158))
const NODE_H = computed(() => (props.compact ? 34 : 42))

const graph = computed(() => layoutGraph(props.lines, props.seasons, {
  ...props.options, nodeW: NODE_W.value, nodeH: NODE_H.value, gapX: props.compact ? 22 : 36, gapY: props.compact ? 6 : 10,
  colGap: props.compact ? 12 : 28, headH: props.compact ? 28 : 36,
}))

const byId = computed(() => new Map(props.lines.map((l) => [l.id, l])))
const kids = computed(() => childrenMap(props.lines))

/** 选中品系的祖先、后代和它自己 */
const related = computed<Set<number> | null>(() => {
  if (props.selectedId == null) return null
  const s = new Set<number>([props.selectedId])
  ancestors(props.selectedId, byId.value).forEach((x) => s.add(x))
  descendants(props.selectedId, kids.value).forEach((x) => s.add(x))
  if (props.secondId != null) s.add(props.secondId)
  return s
})

function dim(id: number) {
  return related.value != null && !related.value.has(id)
}

function edgeOn(from: number, to: number) {
  return related.value != null && related.value.has(from) && related.value.has(to)
}

function onClick(e: MouseEvent, id: number) {
  emit('select', id, e.ctrlKey || e.metaKey || e.shiftKey)
}

function onDragStart(e: DragEvent, l: LineRow) {
  if (!props.draggable || !e.dataTransfer) return
  e.dataTransfer.setData('application/x-line-id', String(l.id))
  e.dataTransfer.setData('text/plain', l.name)
  e.dataTransfer.effectAllowed = 'copy'
  emit('dragline', l.id)
}

const W = computed(() => Math.max(graph.value.width, 200))
const H = computed(() => Math.max(graph.value.height, 80))

defineExpose({
  /** 品系卡片的位置，用来滚动到它 */
  positionOf: (id: number) => {
    const n = graph.value.nodes.find((x) => x.line.id === id)
    return n ? { x: n.x * props.zoom, y: n.y * props.zoom, w: NODE_W.value * props.zoom, h: NODE_H.value * props.zoom } : null
  },
})
</script>

<template>
  <div class="pedigree" :class="{ compact }" :style="{ width: W * zoom + 'px', height: H * zoom + 'px' }">
    <div class="stage" :style="{ width: W + 'px', height: H + 'px', transform: `scale(${zoom})` }">
      <svg class="lines" :width="W" :height="H" aria-hidden="true">
        <g v-for="c in graph.columns" :key="String(c.seasonId)">
          <rect :x="c.x - 8" y="0" :width="c.width + 16" :height="H" rx="8" class="col-bg" :class="{ loose: c.seasonId == null }" />
        </g>
        <path
          v-for="e in graph.edges"
          :key="`${e.from}-${e.to}-${e.role}`"
          :d="e.path"
          class="edge"
          :class="[e.role, { on: edgeOn(e.from, e.to), off: related && !edgeOn(e.from, e.to) }]"
        />
      </svg>
      <div
        v-for="c in graph.columns"
        :key="'h' + String(c.seasonId)"
        class="col-head"
        :style="{ left: c.x + 'px', width: c.width + 'px' }"
        :title="c.title"
      >{{ c.title }}</div>
      <div
        v-for="n in graph.nodes"
        :key="n.line.id"
        class="node"
        :class="[n.line.kind.toLowerCase(), n.line.status.toLowerCase(), {
          selected: n.line.id === selectedId, second: n.line.id === secondId,
          dim: dim(n.line.id), marked: marked?.has(n.line.id),
        }]"
        :style="{ left: n.x + 'px', top: n.y + 'px', width: NODE_W + 'px', height: NODE_H + 'px' }"
        :draggable="draggable"
        :title="`${n.line.name}（${n.line.code}）\n${LINE_KIND[n.line.kind]} · ${n.line.parentText}\n系谱式：${n.line.pedigree}`"
        @click="onClick($event, n.line.id)"
        @dblclick="emit('open', n.line.id)"
        @dragstart="onDragStart($event, n.line)"
      >
        <div class="name">{{ n.line.name }}</div>
        <div class="sub">
          <span v-if="n.line.name !== n.line.code" class="code">{{ n.line.code }}</span>
          <span v-else-if="!compact" class="code muted">{{ n.line.kind === 'FOUNDER' ? '原始材料' : n.line.parentText }}</span>
          <span v-if="latestGeneration(n.line)" class="gen">{{ latestGeneration(n.line) }}</span>
        </div>
        <button
          v-if="n.line.kind === 'CROSS' && (n.hidden > 0 || n.line.childCount > 0)"
          type="button"
          class="fold"
          :title="n.hidden > 0 ? `展开从它选出的 ${n.hidden} 个后代` : '折叠从它选出的后代'"
          @click.stop="emit('toggle', n.line.id)"
          @dblclick.stop
        >{{ n.hidden > 0 ? `+${n.hidden}` : '−' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pedigree {
  position: relative;
}

.stage {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
}

.lines {
  position: absolute;
  left: 0;
  top: 0;
  overflow: visible;
}

.col-bg {
  fill: var(--surface-2);
  opacity: 0.55;
}

.col-bg.loose {
  fill: none;
  stroke: var(--line-strong);
  stroke-dasharray: 4 4;
}

.col-head {
  position: absolute;
  top: 6px;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: var(--fs-sm);
  color: var(--forest);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.compact .col-head {
  top: 4px;
  font-size: var(--fs-xs);
}

.edge {
  fill: none;
  stroke: var(--ink-4);
  stroke-width: 1.4;
}

.edge.father {
  stroke-dasharray: 6 4;
}

.edge.parent {
  stroke-dasharray: 2 3;
}

.edge.on {
  stroke: var(--wheat);
  stroke-width: 2.2;
}

.edge.off {
  opacity: 0.25;
}

.node {
  position: absolute;
  box-sizing: border-box;
  padding: 4px 8px 4px 10px;
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--green);
  border-radius: 6px;
  background: var(--surface);
  cursor: pointer;
  user-select: none;
  transition: opacity 0.15s, box-shadow 0.15s;
  overflow: hidden;
}

.node.founder {
  border-left-color: var(--wheat);
}

.node.cross {
  border-left-color: var(--forest);
}

.node.dropped {
  border-style: dashed;
  border-left-style: solid;
  opacity: 0.7;
  background: var(--surface-2);
}

.node.released {
  background: var(--wheat-wash);
}

.node:hover {
  box-shadow: 0 2px 8px rgb(0 0 0 / 0.12);
}

.node.selected {
  border-color: var(--wheat);
  box-shadow: 0 0 0 2px var(--wheat-bright);
}

.node.second {
  border-color: var(--sky);
  box-shadow: 0 0 0 2px var(--sky);
}

.node.marked {
  background: var(--green-wash);
}

.node.dim {
  opacity: 0.32;
}

.node[draggable='true'] {
  cursor: grab;
}

.name {
  font-weight: 600;
  font-size: var(--fs-sm);
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding-right: 18px;
}

.compact .name {
  font-size: var(--fs-xs);
}

.sub {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 11px;
  color: var(--ink-3);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
}

.code {
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: var(--font-mono);
}

.code.muted {
  font-family: inherit;
}

.gen {
  margin-left: auto;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--sky-soft);
  color: var(--sky);
  font-weight: 600;
}

.fold {
  position: absolute;
  top: 3px;
  right: 3px;
  min-width: 18px;
  height: 16px;
  padding: 0 3px;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: 10px;
  line-height: 14px;
  cursor: pointer;
}

.fold:hover {
  border-color: var(--wheat);
  color: var(--wheat-ink);
}
</style>
