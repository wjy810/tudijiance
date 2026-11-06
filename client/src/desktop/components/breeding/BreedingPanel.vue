<script setup lang="ts">
// 平面图右侧（育种的地块）：
//   布置试验时上面是"种什么"：接着的季种过的品系、它们的子代、这一季已种的、最近种过的，可以搜，拖到平面图上就种下；
//   下面是精简版系谱图：选中小区块时是它种的品系的家系，没选时是这块地块种的品系和它们的父母。
import { computed, ref, watch } from 'vue'
import { Plus, Search } from '@element-plus/icons-vue'

import type { Candidates, LineRow } from '@/shared/api'
import { ancestors, childrenMap, descendants, parentIds } from '@/shared/pedigree'
import { useBreedingStore } from '@/stores/breeding'
import LineChip from './LineChip.vue'
import PedigreeCanvas from './PedigreeCanvas.vue'
import FounderDialog from './FounderDialog.vue'

const props = defineProps<{
  candidates: Candidates | null
  layoutMode: boolean
  teacher: boolean
  /** 选中的小区块种的品系 */
  focusLineIds: number[]
  /** 这块地块种的品系 */
  plantedHere: Set<number>
  /** 选了几个小区块：大于 0 时点品系就种到选中的小区块 */
  selectionCount: number
}>()
const emit = defineEmits<{ plant: [lineId: number]; open: [lineId: number]; locate: [lineId: number] }>()

const store = useBreedingStore()
const search = ref('')
const picked = ref<number | null>(null)
const open = ref<string[]>(['prev', 'children', 'new', 'season'])
const founderOpen = ref(false)

watch(() => props.focusLineIds, () => {
  picked.value = null
})

const hits = computed<LineRow[]>(() => {
  const k = search.value.trim()
  if (!k) return []
  return store.lines.filter((l) => l.name.includes(k) || l.code.includes(k) || (l.formerNames ?? '').includes(k)).slice(0, 40)
})

function onChip(l: LineRow) {
  if (props.layoutMode && props.teacher && props.selectionCount > 0) {
    emit('plant', l.id)
  } else {
    picked.value = l.id
  }
}

/** 精简版系谱图画哪些品系 */
const focusIds = computed(() => (picked.value != null ? [picked.value] : props.focusLineIds))
const only = computed(() => {
  const s = new Set<number>()
  const kids = childrenMap(store.lines)
  if (focusIds.value.length) {
    for (const id of focusIds.value) {
      s.add(id)
      ancestors(id, store.lineById).forEach((x) => s.add(x))
      descendants(id, kids).forEach((x) => s.add(x))
    }
  } else {
    for (const id of props.plantedHere) {
      s.add(id)
      const l = store.lineById.get(id)
      if (l) parentIds(l).forEach((p) => s.add(p))
    }
  }
  return s
})
const selectedId = computed(() => focusIds.value.length === 1 ? focusIds.value[0]! : null)
</script>

<template>
  <div class="breeding-panel">
    <section v-if="layoutMode" class="panel panel-pad">
      <div class="panel-title">
        种什么
        <el-button v-if="teacher" link type="primary" size="small" :icon="Plus" @click="founderOpen = true">新建原始材料</el-button>
      </div>
      <el-input v-model="search" :prefix-icon="Search" size="small" placeholder="搜全部品系" clearable class="search" />
      <div v-if="search.trim()" class="chips">
        <LineChip v-for="l in hits" :key="l.id" :line="l" :draggable="teacher" :active="picked === l.id" @click="onChip(l)" />
        <div v-if="hits.length === 0" class="hint">没找到</div>
      </div>
      <template v-else>
        <div v-if="candidates?.fresh" class="hint">"全新开始"的季：搜品系，或者新建原始材料。</div>
        <el-collapse v-model="open" class="groups">
          <template v-for="g in candidates?.groups ?? []" :key="g.key">
            <el-collapse-item v-if="g.lines.length" :name="g.key">
              <template #title>{{ g.title }}<span class="n">{{ g.lines.length }}</span></template>
              <div class="chips">
                <LineChip
                  v-for="l in g.lines" :key="l.id" :line="l" :draggable="teacher" :active="picked === l.id"
                  @click="onChip(l)"
                />
              </div>
            </el-collapse-item>
          </template>
        </el-collapse>
      </template>
      <div v-if="teacher" class="hint how">
        拖到小区块上种这一格；拖到区块名称牌上整块种；先选好几个小区块再拖到其中一个上，一次种好几个。
        {{ selectionCount > 0 ? `现在点一个品系就种到选中的 ${selectionCount} 个小区块。` : '' }}
      </div>
    </section>

    <section class="panel panel-pad">
      <div class="panel-title">
        系谱
        <span class="sub">{{ focusIds.length ? '选中的品系和它的祖先、后代' : '这块地块种的品系和它们的父母' }}</span>
      </div>
      <div v-if="only.size" class="mini">
        <PedigreeCanvas
          :lines="store.lines"
          :seasons="store.seasons"
          :options="{ only, showDropped: true }"
          :selected-id="selectedId"
          :marked="plantedHere"
          :draggable="layoutMode && teacher"
          compact
          @select="(id: number) => (picked = id)"
          @open="(id: number) => emit('open', id)"
        />
      </div>
      <div v-else class="hint">还没有种品系。</div>
      <div v-if="picked != null || selectedId != null" class="mini-actions">
        <el-button link type="primary" size="small" @click="emit('open', (picked ?? selectedId)!)">品系详情</el-button>
        <el-button link type="primary" size="small" @click="emit('locate', (picked ?? selectedId)!)">在地块上找</el-button>
      </div>
      <div class="hint">绿底的是这块地块种的；双击看详情。</div>
    </section>
    <FounderDialog v-model="founderOpen" />
  </div>
</template>

<style scoped>
.breeding-panel {
  display: contents;
}

.search {
  margin-bottom: 8px;
}

.groups {
  border: none;
  --el-collapse-header-height: 34px;
}

.groups :deep(.el-collapse-item__header) {
  font-size: var(--fs-sm);
  font-weight: 600;
  background: transparent;
}

.groups :deep(.el-collapse-item__wrap) {
  background: transparent;
}

.groups :deep(.el-collapse-item__content) {
  padding-bottom: 8px;
}

.n {
  margin-left: 6px;
  color: var(--ink-4);
  font-weight: 400;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  max-height: 200px;
  overflow: auto;
}

.how {
  margin-top: 8px;
}

.mini {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  padding: 4px;
}

.mini-actions {
  margin-top: 4px;
}
</style>
