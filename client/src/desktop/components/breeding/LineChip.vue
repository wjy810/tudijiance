<script setup lang="ts">
// 一个品系的小牌子：名称、编号、世代；能拖到平面图上种
import type { LineRow } from '@/shared/api'
import { latestGeneration } from '@/shared/pedigree'

defineProps<{ line: LineRow; draggable?: boolean; active?: boolean }>()

function onDragStart(e: DragEvent, l: LineRow) {
  if (!e.dataTransfer) return
  e.dataTransfer.setData('application/x-line-id', String(l.id))
  e.dataTransfer.setData('text/plain', l.name)
  e.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <div
    class="line-chip"
    :class="[line.kind.toLowerCase(), { active, dropped: line.status === 'DROPPED' }]"
    :draggable="draggable"
    :title="`${line.name}（${line.code}）\n${line.parentText}\n系谱式：${line.pedigree}`"
    @dragstart="draggable && onDragStart($event, line)"
  >
    <span class="name">{{ line.name }}</span>
    <span v-if="line.name !== line.code" class="code">{{ line.code }}</span>
    <span v-if="latestGeneration(line)" class="gen">{{ latestGeneration(line) }}</span>
  </div>
</template>

<style scoped>
.line-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 2px 8px 2px 6px;
  border: 1px solid var(--line-strong);
  border-left: 3px solid var(--green);
  border-radius: 5px;
  background: var(--surface);
  font-size: var(--fs-xs);
  cursor: pointer;
  user-select: none;
}

.line-chip[draggable='true'] {
  cursor: grab;
}

.line-chip.founder {
  border-left-color: var(--wheat);
}

.line-chip.cross {
  border-left-color: var(--forest);
}

.line-chip.dropped {
  opacity: 0.6;
  border-style: dashed;
}

.line-chip.active {
  border-color: var(--wheat);
  box-shadow: 0 0 0 1px var(--wheat-bright);
}

.line-chip:hover {
  background: var(--surface-2);
}

.name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.code {
  font-family: var(--font-mono);
  color: var(--ink-3);
  white-space: nowrap;
}

.gen {
  padding: 0 3px;
  border-radius: 3px;
  background: var(--sky-soft);
  color: var(--sky);
  font-weight: 600;
}
</style>
